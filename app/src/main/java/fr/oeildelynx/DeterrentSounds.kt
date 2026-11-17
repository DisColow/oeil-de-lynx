package fr.oeildelynx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sons dissuasifs joués à distance sur le téléphone caméra pour détourner un chat d’une bêtise.
 * Tous sont synthétisés par calcul (bruit filtré, oscillateurs, enveloppes) : pas de fichier audio,
 * pas de droits d’auteur, et un niveau proche du maximum (saturation douce) pour porter loin.
 * Pur JVM pour être testé ; [render] renvoie du PCM 16 bits mono.
 */
internal object DeterrentSounds {
    const val RATE = 44_100

    enum class Effect(val id: String, val label: String, val description: String) {
        HISS("hiss", "Feulement", "Le « khhh » d’un chat qui menace"),
        GROWL_HISS("growl", "Grognement + feulement", "Grondement grave puis feulement"),
        AIR("air", "Bombe d’air « pschht »", "Jet d’air comprimé, très efficace sur les chats"),
        CLAP("clap", "Claquements de mains", "Trois claquements secs"),
        BARK("bark", "Aboiements", "Deux aboiements de chien"),
        KNOCK("knock", "Coups à la porte", "Toc toc toc"),
        WHISTLE("whistle", "Sifflet strident", "Sifflet à roulette, perçant"),
        HIGH("high", "Son très aigu", "Bip aigu désagréable (selon le haut-parleur)"),
        FOIL("foil", "Papier alu froissé", "Crépitement que les chats détestent"),
        HORN("horn", "Corne", "Coup de corne grave et puissant");

        companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } }
    }

    fun render(effect: Effect, seed: Int = 7): ShortArray {
        val random = Random(seed)
        val signal = when (effect) {
            Effect.HISS -> hiss(random, 1.4)
            Effect.GROWL_HISS -> sequence(growl(random, 0.9), silence(0.05), hiss(random, 1.2))
            Effect.AIR -> air(random, 1.6)
            Effect.CLAP -> sequence(*Array(3) { sequence(clap(random), silence(0.22)) })
            Effect.BARK -> sequence(bark(random), silence(0.18), bark(random, 0.92))
            Effect.KNOCK -> sequence(*Array(3) { sequence(knock(random), silence(0.16)) })
            Effect.WHISTLE -> whistle(1.3)
            Effect.HIGH -> sequence(*Array(3) { sequence(tone(15_000.0, 0.35), silence(0.12)) })
            Effect.FOIL -> foil(random, 1.6)
            Effect.HORN -> horn(1.4)
        }
        return toPcm(signal)
    }

    // ——— Briques de synthèse ———

    private fun samples(seconds: Double) = (seconds * RATE).toInt()
    private fun silence(seconds: Double) = DoubleArray(samples(seconds))
    private fun noise(random: Random, n: Int) = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }

    /** Passe-bande (biquad RBJ) : garde la zone de fréquences qui fait le caractère du son. */
    private fun bandPass(input: DoubleArray, center: Double, q: Double): DoubleArray {
        val w = 2 * PI * center / RATE
        val alpha = sin(w) / (2 * q)
        val cos = kotlin.math.cos(w)
        val b0 = alpha; val b2 = -alpha; val a0 = 1 + alpha; val a1 = -2 * cos; val a2 = 1 - alpha
        val out = DoubleArray(input.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in input.indices) {
            val x = input[i]
            val y = (b0 * x + b2 * x2 - a1 * y1 - a2 * y2) / a0
            out[i] = y; x2 = x1; x1 = x; y2 = y1; y1 = y
        }
        return out
    }

    private fun highPass(input: DoubleArray, cutoff: Double): DoubleArray {
        val rc = 1.0 / (2 * PI * cutoff); val dt = 1.0 / RATE; val a = rc / (rc + dt)
        val out = DoubleArray(input.size); var prevX = 0.0; var prevY = 0.0
        for (i in input.indices) { val y = a * (prevY + input[i] - prevX); out[i] = y; prevX = input[i]; prevY = y }
        return out
    }

    /** Enveloppe attaque / maintien / relâchement. */
    private fun envelope(n: Int, attack: Double, release: Double) = DoubleArray(n) { i ->
        val t = i.toDouble() / RATE; val end = n.toDouble() / RATE
        when { t < attack -> t / attack; t > end - release -> ((end - t) / release).coerceAtLeast(0.0); else -> 1.0 }
    }

    private fun normalize(input: DoubleArray, peak: Double = 1.0): DoubleArray {
        val max = input.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: return input
        return DoubleArray(input.size) { input[it] / max * peak }
    }

    private operator fun DoubleArray.times(other: DoubleArray) = DoubleArray(size) { this[it] * other.getOrElse(it) { 0.0 } }

    // ——— Sons ———

    /** Feulement : souffle large et rauque (2–7 kHz), attaque brusque, légers à-coups. */
    private fun hiss(random: Random, seconds: Double): DoubleArray {
        val n = samples(seconds)
        val body = normalize(bandPass(noise(random, n), 3_800.0, 0.6)).mix(normalize(bandPass(noise(random, n), 6_500.0, 1.2), 0.5))
        val flutter = DoubleArray(n) { 0.8 + 0.2 * sin(2 * PI * 17 * it / RATE) }
        return normalize(body * flutter * envelope(n, 0.015, 0.25))
    }

    /** Superpose deux sons (même début). */
    private fun DoubleArray.mix(other: DoubleArray) = DoubleArray(maxOf(size, other.size)) { getOrElse(it) { 0.0 } + other.getOrElse(it) { 0.0 } }

    /** Enchaîne des sons l’un après l’autre. */
    private fun sequence(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size }); var at = 0
        for (part in parts) { part.copyInto(out, at); at += part.size }
        return out
    }

    /** Grondement : bruit grave modulé à ~25 Hz (roulement de gorge) + fondamentale vers 90 Hz. */
    private fun growl(random: Random, seconds: Double): DoubleArray {
        val n = samples(seconds)
        val rumble = normalize(bandPass(noise(random, n), 160.0, 1.5))
        val voice = DoubleArray(n) { i -> val t = i.toDouble() / RATE; sin(2 * PI * 90 * t) + 0.5 * sin(2 * PI * 180 * t) + 0.3 * sin(2 * PI * 270 * t) }
        val roll = DoubleArray(n) { 0.55 + 0.45 * sin(2 * PI * 25 * it / RATE) }
        return normalize((rumble * DoubleArray(n) { 0.6 }).mix(normalize(voice, 0.7)) * roll * envelope(n, 0.08, 0.1))
    }

    /** Bombe d’air : souffle aigu continu (> 3 kHz), démarrage instantané. */
    private fun air(random: Random, seconds: Double): DoubleArray {
        val n = samples(seconds)
        return normalize(highPass(highPass(noise(random, n), 3_000.0), 3_000.0) * envelope(n, 0.005, 0.15))
    }

    /** Claquement : impulsion de bruit très brève (≈ 1 kHz – 3 kHz), décroissance rapide. */
    private fun clap(random: Random): DoubleArray {
        val n = samples(0.12)
        val burst = bandPass(noise(random, n), 1_800.0, 0.8)
        return normalize(DoubleArray(n) { burst[it] * exp(-it.toDouble() / RATE * 45) })
    }

    /** Aboiement : voix harmonique dont la hauteur chute (≈ 600 → 380 Hz), formant vers 1 kHz, souffle. */
    private fun bark(random: Random, pitch: Double = 1.0): DoubleArray {
        val n = samples(0.26)
        var phase = 0.0
        val voiced = DoubleArray(n) { i ->
            val t = i.toDouble() / n
            val f0 = (600 - 220 * t) * pitch
            phase += 2 * PI * f0 / RATE
            (1..8).sumOf { h -> sin(h * phase) / h }
        }
        val shaped = bandPass(voiced, 1_000.0, 0.9).mix(normalize(bandPass(noise(random, n), 2_000.0, 1.0), 0.25))
        val env = DoubleArray(n) { i -> val t = i.toDouble() / RATE; if (t < 0.015) t / 0.015 else exp(-(t - 0.015) * 9) }
        return normalize(shaped * env)
    }

    /** Coup à la porte : choc grave amorti (≈ 120 Hz) + claquement du bois. */
    private fun knock(random: Random): DoubleArray {
        val n = samples(0.16)
        val thump = DoubleArray(n) { i -> val t = i.toDouble() / RATE; sin(2 * PI * 120 * t) * exp(-t * 30) }
        val wood = bandPass(noise(random, n), 900.0, 2.0)
        return normalize(thump.mix(DoubleArray(n) { wood[it] * 0.6 * exp(-it.toDouble() / RATE * 80) }))
    }

    /** Sifflet à roulette : 3 kHz avec trille rapide. */
    private fun whistle(seconds: Double): DoubleArray {
        val n = samples(seconds); var phase = 0.0
        val wave = DoubleArray(n) { i ->
            val f = 3_100 + 180 * sin(2 * PI * 32 * i / RATE)
            phase += 2 * PI * f / RATE
            sin(phase) + 0.25 * sin(2 * phase)
        }
        return normalize(wave * envelope(n, 0.02, 0.08))
    }

    private fun tone(frequency: Double, seconds: Double): DoubleArray {
        val n = samples(seconds)
        return DoubleArray(n) { sin(2 * PI * frequency * it / RATE) } * envelope(n, 0.01, 0.02)
    }

    /** Papier alu : crépitements aléatoires (micro-impulsions aiguës de densité variable). */
    private fun foil(random: Random, seconds: Double): DoubleArray {
        val n = samples(seconds)
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            val length = random.nextInt(40, 400)
            val amp = random.nextDouble(0.3, 1.0)
            for (k in 0 until length) if (i + k < n) out[i + k] += random.nextDouble(-amp, amp) * exp(-k / 60.0)
            i += random.nextInt(80, 1_400)
        }
        return normalize(highPass(out, 2_000.0) * envelope(n, 0.01, 0.1))
    }

    /** Corne : accord grave dissonant (220 + 233 Hz) en ondes carrées adoucies, très puissant. */
    private fun horn(seconds: Double): DoubleArray {
        val n = samples(seconds)
        val wave = DoubleArray(n) { i ->
            val t = i.toDouble() / RATE
            tanh(4 * sin(2 * PI * 220 * t)) + tanh(4 * sin(2 * PI * 233 * t)) + 0.5 * tanh(4 * sin(2 * PI * 440 * t))
        }
        return normalize(wave * envelope(n, 0.03, 0.15))
    }

    /** Saturation douce : son plus fort perçu sans écrêtage brutal, puis conversion 16 bits. */
    private fun toPcm(signal: DoubleArray): ShortArray {
        val loud = DoubleArray(signal.size) { tanh(signal[it] * 2.2) / tanh(2.2) }
        return ShortArray(loud.size) { (loud[it].coerceIn(-1.0, 1.0) * 32_000).toInt().toShort() }
    }
}
