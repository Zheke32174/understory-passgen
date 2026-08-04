package com.understory.keyboard.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.understory.keyboard.plugin.api.FieldContext
import com.understory.keyboard.plugin.api.IKotobaPlugin
import com.understory.keyboard.plugin.api.PluginContract
import com.understory.keyboard.plugin.api.PluginManifest
import com.understory.keyboard.plugin.api.QuickAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bound-service client for enabled plugins. All binder traffic runs on a
 * private worker thread — the IME's main thread only ever receives posted
 * results. A plugin that throws, hangs, or dies yields empty results, not
 * a keyboard crash.
 */
class PluginClient(
    private val context: Context,
    private val registry: PluginRegistry,
) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kotoba-plugin").apply { isDaemon = true }
    }
    private val connections = ConcurrentHashMap<ComponentName, Conn>()
    private val manifests = ConcurrentHashMap<ComponentName, PluginManifest>()

    /** Snapshot of usable plugins, refreshed by [refresh]. */
    @Volatile
    var plugins: List<PluginRegistry.PluginEntry> = emptyList()
        private set

    private class Conn : ServiceConnection {
        @Volatile var binder: IKotobaPlugin? = null
        val connected = CountDownLatch(1)

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = IKotobaPlugin.Stub.asInterface(service)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }

        fun awaitBinder(timeoutMs: Long): IKotobaPlugin? {
            connected.await(timeoutMs, TimeUnit.MILLISECONDS)
            return binder
        }
    }

    /**
     * Re-read the usable plugin set and pre-fetch manifests off-thread.
     * [onReady] is invoked from the worker thread once manifests are
     * cached (post to the main thread before touching views).
     */
    fun refresh(onReady: () -> Unit = {}) {
        executor.execute {
            val usable = registry.usablePlugins()
            plugins = usable
            // Drop connections/manifests for plugins that are gone or revoked.
            val valid = usable.map { it.component }.toSet()
            for (component in connections.keys) {
                if (component !in valid) disconnect(component)
            }
            manifests.keys.retainAll(valid)
            for (p in usable) manifestFor(p.component)
            onReady()
        }
    }

    /** Cached quick-action chips: (plugin, action) pairs, manager order. */
    fun actionChips(): List<Pair<PluginRegistry.PluginEntry, QuickAction>> =
        plugins.flatMap { p ->
            val m = manifests[p.component] ?: return@flatMap emptyList()
            if (PluginContract.CAP_ACTIONS !in m.capabilities) return@flatMap emptyList()
            m.actions.take(MAX_ACTIONS_PER_PLUGIN).map { p to it }
        }

    /**
     * Collect suggestions from every suggestion-capable plugin. [onResult]
     * fires once, from the worker thread, with the merged list.
     */
    fun suggestions(
        composing: String,
        beforeCursor: String,
        ctx: FieldContext,
        onResult: (List<String>) -> Unit,
    ) {
        executor.execute {
            val out = mutableListOf<String>()
            for (p in plugins) {
                val m = manifests[p.component] ?: continue
                if (PluginContract.CAP_SUGGESTIONS !in m.capabilities) continue
                call(p.component) { it.getSuggestions(composing, beforeCursor, ctx) }
                    ?.filterNotNull()
                    ?.take(MAX_SUGGESTIONS_PER_PLUGIN)
                    ?.let { out += it }
            }
            onResult(out)
        }
    }

    /** Run one quick action; [onResult] fires once from the worker thread. */
    fun performAction(
        component: ComponentName,
        actionId: String,
        selectedText: String,
        ctx: FieldContext,
        onResult: (String?) -> Unit,
    ) {
        executor.execute {
            onResult(call(component) { it.performAction(actionId, selectedText, ctx) })
        }
    }

    fun shutdown() {
        for (component in connections.keys) disconnect(component)
        executor.shutdown()
    }

    // -- internals (worker thread only) ------------------------------------

    private fun manifestFor(component: ComponentName): PluginManifest? {
        manifests[component]?.let { return it }
        val m = call(component) { it.describe() } ?: return null
        if (m.apiVersion != PluginContract.API_VERSION) return null
        manifests[component] = m
        return m
    }

    private fun <T> call(component: ComponentName, block: (IKotobaPlugin) -> T): T? {
        val conn = connect(component) ?: return null
        val binder = conn.awaitBinder(BIND_TIMEOUT_MS) ?: return null
        return try {
            block(binder)
        } catch (t: Throwable) {
            // DeadObjectException, RemoteException, or a misbehaving plugin:
            // drop the connection so the next call rebinds fresh.
            disconnect(component)
            null
        }
    }

    private fun connect(component: ComponentName): Conn? {
        connections[component]?.let { return it }
        val conn = Conn()
        val intent = Intent(PluginContract.ACTION_PLUGIN_SERVICE).setComponent(component)
        val bound = runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            runCatching { context.unbindService(conn) }
            return null
        }
        connections[component] = conn
        return conn
    }

    private fun disconnect(component: ComponentName) {
        connections.remove(component)?.let { conn ->
            runCatching { context.unbindService(conn) }
        }
        manifests.remove(component)
    }

    companion object {
        private const val BIND_TIMEOUT_MS = 400L
        private const val MAX_SUGGESTIONS_PER_PLUGIN = 3
        private const val MAX_ACTIONS_PER_PLUGIN = 4
    }
}
