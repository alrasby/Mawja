package com.mawja.player.data

/** Small synchronized LRU: expired values are removed on reads and writes, without timers. */
internal class BoundedTtlCache<K, V>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init { require(maxEntries > 0 && ttlMs > 0) }
    private data class Entry<V>(val value: V, val created: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    @Synchronized operator fun get(key: K): V? {
        prune()
        return entries[key]?.value
    }
    @Synchronized operator fun set(key: K, value: V) {
        prune()
        entries[key] = Entry(value, clock())
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }
    @Synchronized fun remove(key: K) { entries.remove(key) }
    @Synchronized fun size(): Int { prune(); return entries.size }
    private fun prune() {
        val now = clock()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val age = now - iterator.next().created
            if (age < 0 || age >= ttlMs) iterator.remove()
        }
    }
}
