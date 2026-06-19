package com.example.tp5_bluetooth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import java.util.LinkedList
import kotlin.math.abs

// ══════════════════════════════════════════════════════════════════════════════
// MultiGraphView — Graphe à plusieurs courbes (une par capteur), échelle commune
// ══════════════════════════════════════════════════════════════════════════════
class MultiGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val MAX_POINTS = 100

    private val series: Array<LinkedList<Float>> = Array(6) { LinkedList() }

    var seriesColors: IntArray = intArrayOf(
        Color.parseColor("#FFBB33"), // poignet_g
        Color.parseColor("#FF6B6B"), // epaule_g
        Color.parseColor("#00E5FF"), // poignet_d
        Color.parseColor("#69FF47"), // epaule_d
        Color.parseColor("#D070FF"), // tete
        Color.parseColor("#FF70C0")  // nuque
    )
    var seriesLabels: Array<String> = arrayOf(
        "poignet G", "épaule G", "poignet D", "épaule D", "tête", "nuque"
    )

    var label: String = ""
    var unit: String = ""

    var fixedYMin: Float? = null
    var fixedYMax: Float? = null

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }
    private val paintLegend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
    }

    /** Ajoute une valeur à la série [index] (0-5) sans redessiner immédiatement. */
    fun addValue(index: Int, v: Float) {
        if (index !in 0..5) return
        val s = series[index]
        if (s.size >= MAX_POINTS) s.removeFirst()
        s.addLast(v)
    }

    /** À appeler une fois après un ou plusieurs addValue() pour rafraîchir l'affichage. */
    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 8f
        val padR = 8f
        val padT = 40f
        val padB = 32f

        canvas.drawColor(Color.argb(200, 15, 15, 30))

        val maxSize = series.maxOf { it.size }
        if (maxSize < 2) {
            paintText.color = Color.argb(120, 255, 255, 255)
            canvas.drawText(label, padL + 4, padT - 8, paintText)
            return
        }

        val allValues = series.toList().flatten()
        val minVal = fixedYMin ?: (allValues.minOrNull() ?: 0f)
        val maxVal = fixedYMax ?: (allValues.maxOrNull() ?: 1f)
        val range  = if (maxVal - minVal < 0.001f) 1f else maxVal - minVal

        val chartW = w - padL - padR
        val chartH = h - padT - padB

        fun xOf(i: Int, n: Int) = padL + i * chartW / (n - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH - (v - minVal) / range * chartH

        if (minVal < 0f && maxVal > 0f) {
            val y0 = yOf(0f)
            canvas.drawLine(padL, y0, w - padR, y0, paintAxis)
        }

        series.forEachIndexed { si, s ->
            if (s.size < 2) return@forEachIndexed
            val path = Path()
            s.forEachIndexed { i, v ->
                val x = xOf(i, s.size); val y = yOf(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paintLine.color = seriesColors[si]
            canvas.drawPath(path, paintLine)
        }

        paintText.color = Color.argb(200, 255, 255, 255)
        paintText.textSize = 26f
        canvas.drawText(label, padL + 4, padT - 10, paintText)

        // Légende : seuls les capteurs qui ont des données sont affichés en pleine opacité
        val legendY = h - 10f
        var legendX = padL + 4f
        seriesLabels.forEachIndexed { i, lbl ->
            val hasData = series[i].isNotEmpty()
            paintLegend.color = seriesColors[i]
            paintLegend.alpha = if (hasData) 255 else 60
            canvas.drawCircle(legendX, legendY - 6f, 5f, paintLegend)
            paintLegend.color = Color.argb(if (hasData) 220 else 80, 255, 255, 255)
            canvas.drawText(lbl, legendX + 10f, legendY, paintLegend)
            legendX += paintLegend.measureText(lbl) + 28f
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Fragment 2 — 4 graphes multi-courbes, parsing tolérant (7 à 42 valeurs)
// ══════════════════════════════════════════════════════════════════════════════
class Fragment2 : Fragment() {

    private lateinit var vue: View
    private lateinit var sharedViewModel: SharedViewModel

    private val lastRx = FloatArray(6)
    private val lastRy = FloatArray(6)
    private val lastRz = FloatArray(6)
    private val lastAct = FloatArray(6)

    private fun filterNoise(raw: Float, last: Float, threshold: Float): Float =
        if (abs(raw - last) < threshold) last else raw

    private fun filterRx(i: Int, raw: Float): Float {
        val f = filterNoise(raw, lastRx[i], 1.0f); lastRx[i] = f; return f
    }
    private fun filterRy(i: Int, raw: Float): Float {
        val f = filterNoise(raw, lastRy[i], 0.2f); lastRy[i] = f; return f
    }
    private fun filterRz(i: Int, raw: Float): Float {
        val f = filterNoise(raw, lastRz[i], 2.0f); lastRz[i] = f; return f
    }
    private fun filterAct(i: Int, raw: Float): Float {
        val clamped = if (raw in 0.2f..0.3f) 0f else raw
        val f = filterNoise(clamped, lastAct[i], 0.1f); lastAct[i] = f; return f
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        vue = inflater.inflate(R.layout.fragment_2, container, false)

        val graphX   = vue.findViewById<MultiGraphView>(R.id.graphX)
        val graphY   = vue.findViewById<MultiGraphView>(R.id.graphY)
        val graphZ   = vue.findViewById<MultiGraphView>(R.id.graphZ)
        val graphAct = vue.findViewById<MultiGraphView>(R.id.graphActimetrie)
        val tvStatus = vue.findViewById<TextView>(R.id.tvStatus)

        graphX.label   = "Rotation X"; graphX.unit   = "°"
        graphY.label   = "Rotation Y"; graphY.unit   = "°"
        graphZ.label   = "Rotation Z"; graphZ.unit   = "°"
        graphAct.label = "Actimétrie"; graphAct.unit = ""
        graphAct.fixedYMin = 0f
        graphAct.fixedYMax = 8f

        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        // Trame complète attendue : 6 capteurs × 7 valeurs = 42
        // Mode tolérant : accepte tout multiple de 7 entre 7 et 42
        // Ordre capteurs : poignet_g(0), epaule_g(1), poignet_d(2), epaule_d(3), tete(4), nuque(5)
        // Ordre valeurs par capteur : rx, ry, rz, magnitude, posX, posY, posZ
        sharedViewModel.codeBarre.observe(viewLifecycleOwner) { raw ->
            if (raw.isNullOrBlank()) return@observe

            val tokens = raw.trim().split(",")
            val prefixToIndex = mapOf(
                "PG" to 0, "EG" to 1, "PD" to 2, "ED" to 3, "TE" to 4, "NU" to 5
            )

            var capteursTrouves = 0
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i].trim()
                val capteurIndex = prefixToIndex[tok]
                if (capteurIndex != null && i + 7 <= tokens.size) {
                    val rx  = tokens[i + 1].trim().toFloatOrNull() ?: 0f
                    val ry  = tokens[i + 2].trim().toFloatOrNull() ?: 0f
                    val rz  = tokens[i + 3].trim().toFloatOrNull() ?: 0f
                    val act = tokens[i + 4].trim().toFloatOrNull() ?: 0f
                    // tokens[i+5], [i+6], [i+7] = posX, posY, posZ → utilisés dans Fragment3

                    graphX.addValue(capteurIndex, filterRx(capteurIndex, rx))
                    graphY.addValue(capteurIndex, filterRy(capteurIndex, ry))
                    graphZ.addValue(capteurIndex, filterRz(capteurIndex, rz))
                    graphAct.addValue(capteurIndex, filterAct(capteurIndex, act))

                    capteursTrouves++
                    i += 8   // préfixe + 7 valeurs
                } else {
                    i++
                }
            }

            if (capteursTrouves > 0) {
                graphX.refresh(); graphY.refresh(); graphZ.refresh(); graphAct.refresh()
                tvStatus.text = "ESP32 connecté ✓ — $capteursTrouves/6 capteurs"
                tvStatus.setTextColor(Color.parseColor("#69FF47"))
            } else {
                tvStatus.text = "Trame reçue, aucun capteur reconnu"
                tvStatus.setTextColor(Color.parseColor("#FF6D00"))
            }
        }

        vue.findViewById<Button>(R.id.btnPage1).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Fragment1()).commit()
        }
        vue.findViewById<Button>(R.id.btnPage2).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Fragment2()).commit()
        }
        vue.findViewById<Button>(R.id.btnPage3).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Fragment3()).commit()
        }

        return vue
    }
}