package org.koitharu.kotatsu.parsers.site.en.kagane

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Randomizer for image scrambling/unscrambling.
 * Port of the Tachiyomi Kagane extension's Randomizer class.
 */
internal class KaganeRandomizer(seedInput: BigInteger, gridSize: Int) {

	val size: Int = gridSize * gridSize
	val seed: BigInteger
	private var state: BigInteger
	private val entropyPool: ByteArray
	val order: MutableList<Int>

	companion object {
		private val MASK64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
		private val MASK32 = BigInteger("FFFFFFFF", 16)
		private val MASK8 = BigInteger("FF", 16)
		private val PRNG_MULT = BigInteger("27BB2EE687B0B0FD", 16)
		private val RND_MULT_32 = BigInteger("45d9f3b", 16)
	}

	init {
		val seedMask = BigInteger("FFFFFFFFFFFFFFFF", 16)
		seed = seedInput.and(seedMask)
		state = hashSeed(seed)
		entropyPool = expandEntropy(seed)
		order = MutableList(size) { it }
		permute()
	}

	private fun hashSeed(e: BigInteger): BigInteger {
		val md = sha256(e.toString())
		return readBigUInt64BE(md, 0).xor(readBigUInt64BE(md, 8))
	}

	private fun readBigUInt64BE(bytes: ByteArray, offset: Int): BigInteger {
		var n = BigInteger.ZERO
		for (i in 0 until 8) {
			n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
		}
		return n
	}

	private fun expandEntropy(e: BigInteger): ByteArray =
		MessageDigest.getInstance("SHA-512").digest(e.toString().toByteArray(Charsets.UTF_8))

	private fun sbox(e: Int): Int {
		val t = intArrayOf(163, 95, 137, 13, 55, 193, 107, 228, 114, 185, 22, 243, 68, 218, 158, 40)
		return t[e and 15] xor t[e shr 4 and 15]
	}

	fun prng(): BigInteger {
		state = state.xor(state.shiftLeft(11).and(MASK64))
		state = state.xor(state.shiftRight(19))
		state = state.xor(state.shiftLeft(7).and(MASK64))
		state = state.multiply(PRNG_MULT).and(MASK64)
		return state
	}

	private fun roundFunc(e: BigInteger, t: Int): BigInteger {
		var n = e.xor(prng()).xor(BigInteger.valueOf(t.toLong()))

		val rot = n.shiftLeft(5).or(n.shiftRight(3)).and(MASK32)
		n = rot.multiply(RND_MULT_32).and(MASK32)

		val sboxVal = sbox(n.and(MASK8).toInt())
		n = n.xor(BigInteger.valueOf(sboxVal.toLong()))

		n = n.xor(n.shiftRight(13))
		return n
	}

	private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
		var r = BigInteger.valueOf(e.toLong())
		var i = BigInteger.valueOf(t.toLong())
		for (round in 0 until rounds) {
			val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
			r = r.xor(roundFunc(i, ent))
			val secondArg = ent xor (round * 31 and 255)
			i = i.xor(roundFunc(r, secondArg))
		}
		return Pair(r, i)
	}

	private fun permute() {
		val half = size / 2
		val sizeBig = BigInteger.valueOf(size.toLong())

		for (t in 0 until half) {
			val n = t + half
			val (rBig, iBig) = feistelMix(t, n, 4)
			val s = rBig.mod(sizeBig).toInt()
			val a = iBig.mod(sizeBig).toInt()
			val tmp = order[s]
			order[s] = order[a]
			order[a] = tmp
		}

		for (e in size - 1 downTo 1) {
			val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
			val idxBig = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong()))
			val n = idxBig.toInt()
			val tmp = order[e]
			order[e] = order[n]
			order[n] = tmp
		}
	}
}

/**
 * Scrambler for generating scramble mappings.
 * Port of the Tachiyomi Kagane extension's Scrambler class.
 */
internal class KaganeScrambler(private val seed: BigInteger, private val gridSize: Int) {

	private val totalPieces: Int = gridSize * gridSize
	private val randomizer: KaganeRandomizer = KaganeRandomizer(seed, gridSize)
	private val dependencyGraph: DependencyGraph
	private val scramblePath: List<Int>

	init {
		dependencyGraph = buildDependencyGraph()
		scramblePath = generateScramblePath()
	}

	private data class DependencyGraph(
		val graph: MutableMap<Int, MutableList<Int>>,
		val inDegree: MutableMap<Int, Int>,
	)

	private fun buildDependencyGraph(): DependencyGraph {
		val graph = mutableMapOf<Int, MutableList<Int>>()
		val inDegree = mutableMapOf<Int, Int>()

		for (n in 0 until totalPieces) {
			inDegree[n] = 0
			graph[n] = mutableListOf()
		}

		val rng = KaganeRandomizer(seed, gridSize)

		for (r in 0 until totalPieces) {
			val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
			repeat(i) {
				val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
				if (j != r && !wouldCreateCycle(graph, j, r)) {
					graph[j]!!.add(r)
					inDegree[r] = inDegree[r]!! + 1
				}
			}
		}

		for (r in 0 until totalPieces) {
			if (inDegree[r] == 0) {
				var tries = 0
				while (tries < 10) {
					val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
					if (s != r && !wouldCreateCycle(graph, s, r)) {
						graph[s]!!.add(r)
						inDegree[r] = inDegree[r]!! + 1
						break
					}
					tries++
				}
			}
		}

		return DependencyGraph(graph, inDegree)
	}

	private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
		val visited = mutableSetOf<Int>()
		val stack = ArrayDeque<Int>()
		stack.add(start)

		while (stack.isNotEmpty()) {
			val n = stack.removeLast()
			if (n == target) return true
			if (!visited.add(n)) continue
			graph[n]?.let { stack.addAll(it) }
		}
		return false
	}

	private fun generateScramblePath(): List<Int> {
		val graphCopy = dependencyGraph.graph.mapValues { it.value.toMutableList() }.toMutableMap()
		val inDegreeCopy = dependencyGraph.inDegree.toMutableMap()

		val queue = ArrayDeque<Int>()
		for (n in 0 until totalPieces) {
			if (inDegreeCopy[n] == 0) {
				queue.add(n)
			}
		}

		val order = mutableListOf<Int>()
		while (queue.isNotEmpty()) {
			val i = queue.removeFirst()
			order.add(i)
			val neighbors = graphCopy[i]
			if (neighbors != null) {
				for (e in neighbors) {
					inDegreeCopy[e] = inDegreeCopy[e]!! - 1
					if (inDegreeCopy[e] == 0) {
						queue.add(e)
					}
				}
			}
		}
		return order
	}

	fun getScrambleMapping(): List<Pair<Int, Int>> {
		var e = randomizer.order.toMutableList()

		if (scramblePath.size == totalPieces) {
			val t = Array(totalPieces) { 0 }
			for (i in scramblePath.indices) {
				t[i] = scramblePath[i]
			}
			val n = Array(totalPieces) { 0 }
			for (r in 0 until totalPieces) {
				n[r] = e[t[r]]
			}
			e = n.toMutableList()
		}

		val result = mutableListOf<Pair<Int, Int>>()
		for (n in 0 until totalPieces) {
			result.add(n to e[n])
		}
		return result
	}
}

/**
 * SHA-256 hash helper
 */
internal fun sha256(input: String): ByteArray {
	return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}
