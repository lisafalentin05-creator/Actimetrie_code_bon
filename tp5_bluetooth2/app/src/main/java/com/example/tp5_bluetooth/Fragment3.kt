package com.example.tp5_bluetooth

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import kotlin.math.*

// ════════════════════════════════════════════════════════════════════════════
//
//   POSTUREVIEW
//   ------------
//   Vue Canvas personnalisée qui dessine une silhouette humaine de profil.
//   Elle reçoit les angles des 6 capteurs (tête, nuque, épaules, poignets)
//   et anime le bonhomme en conséquence.
//
//   Principe général :
//   1. setAll() reçoit les nouvelles valeurs de capteurs et redessine
//   2. onDraw() calcule la géométrie (positions des articulations) et peint
//
// ════════════════════════════════════════════════════════════════════════════
class PostureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ────────────────────────────────────────────────────────────────────────
    // 1. DONNÉES DES CAPTEURS
    // ────────────────────────────────────────────────────────────────────────
    // Ces variables stockent l'angle "pitch" (en degrés) de chaque capteur,
    // tel que calculé par le filtre Madgwick sur l'ESP32. Le pitch représente
    // l'inclinaison avant/arrière du capteur, c'est ce qu'on utilise pour
    // animer le bonhomme (élévation du bras, flexion du coude, etc.)

    private var poignetGy = 0f   // angle du capteur poignet gauche
    private var epauleGy  = 0f   // angle du capteur épaule gauche
    private var poignetDy = 0f   // angle du capteur poignet droit
    private var epauleDy  = 0f   // angle du capteur épaule droit
    private var teteY     = 0f   // angle du capteur tête
    private var nuqueY    = 0f   // angle du capteur nuque

    /**
     * Appelée depuis Fragment3 à chaque nouvelle trame Bluetooth reçue.
     * Met à jour les angles et déclenche un nouveau dessin (invalidate()).
     */
    fun setAll(
        poignetG: Triple<Float, Float, Float>,
        epauleG:  Triple<Float, Float, Float>,
        poignetD: Triple<Float, Float, Float>,
        epauleD:  Triple<Float, Float, Float>,
        tete:     Triple<Float, Float, Float>,
        nuque:    Triple<Float, Float, Float>
    ) {
        // .second correspond à la 2e valeur du triplet (posX, posY, posZ)
        // posY contient en réalité l'angle "pitch" envoyé par l'ESP32
        poignetGy = poignetG.second
        epauleGy  = epauleG.second
        poignetDy = poignetD.second
        epauleDy  = epauleD.second
        teteY     = tete.second
        nuqueY    = nuque.second
        invalidate()  // redemande à Android d'appeler onDraw()
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. PINCEAUX (PAINTS)
    // ────────────────────────────────────────────────────────────────────────
    // Chaque "Paint" définit l'apparence d'un élément dessiné (couleur,
    // épaisseur, style plein/contour...). Les déclarer une fois ici (plutôt
    // que dans onDraw) évite de recréer ces objets à chaque image, ce qui
    // serait coûteux en performance.

    // Corps (torse, jambes, bassin...)
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        color = Color.parseColor("#8090A0")
    }

    // Bras droit (cyan)
    private val paintArmR = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintArmRStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
        color = Color.parseColor("#00C0E0")
    }

    // Bras gauche (orange)
    private val paintArmL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintArmLStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f
        color = Color.parseColor("#FF9F00")
    }

    // Articulations (ronds aux épaules/coudes/mains)
    private val paintJointR = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#00E5FF")
    }
    private val paintJointL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#FFBB33")
    }

    // Colonne vertébrale (ligne pointillée décorative dans le torse)
    private val paintSpine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        color = Color.argb(100, 255, 80, 80)
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // Arc pointillé décoratif derrière les bras (rayon de portée du mouvement)
    private val paintArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        color = Color.argb(50, 0, 229, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    // Grille de fond (repères visuels)
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(25, 255, 255, 255); strokeWidth = 1f
    }

    // Panneau d'info (encadré affichant les valeurs numériques d'angles)
    private val paintInfo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 229, 255); style = Paint.Style.FILL
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        typeface = Typeface.MONOSPACE
    }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true; typeface = Typeface.MONOSPACE
    }

    // Repères capteurs (petits ronds + croix affichés sur le bonhomme)
    private val paintSensor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintSensorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE
    }
    private val paintSensorLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.MONOSPACE
    }
    private val paintSensorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
        color = Color.argb(100, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
    }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        color = Color.argb(200, 255, 255, 255)
    }

    // Couleur attribuée à chaque capteur (cohérente avec Fragment2 / les courbes)
    private val sensorColors = mapOf(
        "tete"      to Color.parseColor("#D070FF"),
        "nuque"     to Color.parseColor("#FF70C0"),
        "epaule_g"  to Color.parseColor("#FF6B6B"),
        "epaule_d"  to Color.parseColor("#69FF47"),
        "poignet_g" to Color.parseColor("#FFBB33"),
        "poignet_d" to Color.parseColor("#00E5FF")
    )

    // ────────────────────────────────────────────────────────────────────────
    // 3. DESSIN PRINCIPAL — appelé automatiquement par Android à chaque
    //    rafraîchissement (déclenché par invalidate() dans setAll())
    // ────────────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0A0A1E"))  // fond bleu nuit

        val w = width.toFloat()
        val h = height.toFloat()

        // --- 3.1 Grille de fond (repères visuels, purement esthétique) ---
        var gx = 0f; while (gx <= w) { canvas.drawLine(gx, 0f, gx, h, paintGrid); gx += 60f }
        var gy = 0f; while (gy <= h) { canvas.drawLine(0f, gy, w, gy, paintGrid); gy += 60f }

        // "unit" est une unité de mesure relative à la hauteur de l'écran :
        // toutes les proportions du corps sont calculées à partir d'elle,
        // ce qui permet au dessin de s'adapter à n'importe quelle taille d'écran.
        val unit = h * 0.09f
        val ox   = w * 0.38f   // position horizontale de l'axe du corps (centre du torse)

        // --- 3.2 Positions anatomiques verticales (tête → pieds) ---
        // Chaque articulation est positionnée en cascade : la tête définit
        // la position du cou, qui définit les épaules, qui définissent
        // le bassin, etc. C'est ce qui garde les proportions cohérentes.
        val headTopY  = h * 0.2f
        val headCy    = headTopY + unit * 0.65f
        val headRx    = unit * 0.42f
        val headRy    = unit * 0.65f
        val neckY     = headCy + headRy
        val shoulderY = neckY + unit * 0.4f
        val hipY      = shoulderY + unit * 1.8f
        val kneeY     = hipY + unit * 1.8f
        val ankleY    = kneeY + unit * 1.7f
        val footEndX  = ox + unit * 0.9f
        val torsoRx   = unit * 0.48f
        val thighRx   = unit * 0.22f
        val thighRy   = (kneeY - hipY) / 2f
        val shinRx    = unit * 0.16f
        val shinRy    = (ankleY - kneeY) / 2f
        val armLen    = unit * 1.2f     // longueur bras supérieur (épaule → coude)
        val forearmLen = unit * 1.15f   // longueur avant-bras (coude → main)
        val armRx     = unit * 0.14f    // épaisseur visuelle du bras supérieur
        val forearmRx = unit * 0.12f    // épaisseur visuelle de l'avant-bras

        // ════════════════════════════════════════════════════════════════════
        // 4. CALCUL DES ANGLES À PARTIR DES CAPTEURS
        // ════════════════════════════════════════════════════════════════════
        // C'est le cœur de la logique d'animation. Les capteurs envoient des
        // angles "pitch" bruts ; on les transforme ici en angles d'élévation
        // (bras qui monte) et de flexion (coude qui plie), avec les signes
        // ajustés empiriquement pour correspondre au mouvement humain réel.
        //
        // ⚠ Les signes (+/-) ci-dessous ont été calibrés sur le terrain :
        // chaque capteur MPU6050 peut être monté dans une orientation physique
        // différente, donc le signe de son pitch ne correspond pas forcément
        // à la convention "positif = vers l'avant" qu'on veut pour l'affichage.

        // Élévation des épaules : angle du bras par rapport au corps.
        // Le signe "-" inverse le capteur pour que "vers l'avant" soit positif.
        val elevD = -epauleDy
        val elevG = -epauleGy

        // Flexion des coudes : différence entre le pitch du poignet et celui
        // de l'épaule. coerceIn(0f, 150f) empêche toute valeur négative
        // (un coude humain ne peut physiquement plier que dans un seul sens).
        val flexD = (poignetDy - epauleDy).coerceIn(0f, 150f)
        val flexG = (epauleGy - poignetGy).coerceIn(0f, 150f)

        // Tête et nuque : flexion avant/arrière, plage limitée pour rester réaliste.
        val headFlex = teteY.coerceIn(-45f, 60f)
        val neckFlex = nuqueY.coerceIn(-30f, 40f)

        // ────────────────────────────────────────────────────────────────────
        // 4.1 Géométrie du bras droit
        // ────────────────────────────────────────────────────────────────────
        // On convertit les angles en radians puis on calcule la position du
        // coude (à distance "armLen" de l'épaule) et de la main (à distance
        // "forearmLen" du coude), via cos()/sin() classique en trigonométrie.
        val upperArmRRad = Math.toRadians((90f + elevD).toDouble()).toFloat()
        val elbowRx = ox + cos(upperArmRRad) * armLen
        val elbowRy = shoulderY + sin(upperArmRRad) * armLen

        val foreRRad = Math.toRadians((90f + elevD - flexD).toDouble()).toFloat()
        val handRx = elbowRx + cos(foreRRad) * forearmLen
        val handRy = elbowRy + sin(foreRRad) * forearmLen

        // ────────────────────────────────────────────────────────────────────
        // 4.2 Géométrie du bras gauche (même principe, miroir horizontal)
        // ────────────────────────────────────────────────────────────────────
        val upperArmLRad = Math.toRadians((90f + elevG).toDouble()).toFloat()
        val elbowLx = ox - cos(upperArmLRad) * armLen
        val elbowLy = shoulderY + sin(upperArmLRad) * armLen

        val foreLRad = Math.toRadians((90f + elevG + flexG).toDouble()).toFloat()
        val handLx = elbowLx - cos(foreLRad) * forearmLen
        val handLy = elbowLy + sin(foreLRad) * forearmLen

        // ════════════════════════════════════════════════════════════════════
        // 5. DESSIN DU CORPS (du fond vers l'avant-plan)
        // ════════════════════════════════════════════════════════════════════
        // L'ordre de dessin compte : on peint d'abord les éléments "derrière"
        // (jambe arrière, torse) puis les éléments "devant" (bras, tête).

        // --- 5.1 Jambe gauche (dessinée en arrière-plan, légèrement décalée) ---
        paintFill.color = Color.parseColor("#506070"); paintFill.alpha = 140
        canvas.drawOval(RectF(ox - thighRx * 0.9f,
            (hipY + kneeY) / 2f + unit * 0.15f - thighRy,
            ox + thighRx * 0.9f,
            (hipY + kneeY) / 2f + unit * 0.15f + thighRy), paintFill)
        canvas.drawOval(RectF(ox - shinRx * 0.85f,
            (kneeY + ankleY) / 2f + unit * 0.1f - shinRy,
            ox + shinRx * 0.85f,
            (kneeY + ankleY) / 2f + unit * 0.1f + shinRy), paintFill)
        paintFill.alpha = 255

        // --- 5.2 Torse ---
        paintFill.color = Color.parseColor("#8090A0")
        canvas.drawRoundRect(RectF(ox - torsoRx * 0.4f, shoulderY, ox + torsoRx, hipY),
            torsoRx * 0.5f, torsoRx * 0.5f, paintFill)
        paintFill.color = Color.parseColor("#607080")  // ombre côté gauche du torse
        canvas.drawRoundRect(RectF(ox - torsoRx * 0.4f, shoulderY, ox, hipY),
            torsoRx * 0.4f, torsoRx * 0.4f, paintFill)
        canvas.drawRoundRect(RectF(ox - torsoRx * 0.4f, shoulderY, ox + torsoRx, hipY),
            torsoRx * 0.5f, torsoRx * 0.5f, paintStroke)
        // Colonne vertébrale (ligne pointillée décorative)
        canvas.drawPath(Path().apply {
            moveTo(ox + torsoRx * 0.1f, shoulderY + unit * 0.2f)
            quadTo(ox + torsoRx * 0.05f, (shoulderY + hipY) / 2f,
                ox + torsoRx * 0.1f, hipY - unit * 0.1f)
        }, paintSpine)

        // --- 5.3 Bassin ---
        paintFill.color = Color.parseColor("#7080A0")
        val pelvisRect = RectF(ox - torsoRx * 0.3f, hipY - unit * 0.3f,
            ox + torsoRx * 0.85f, hipY + unit * 0.35f)
        canvas.drawOval(pelvisRect, paintFill)
        canvas.drawOval(pelvisRect, paintStroke)

        // --- 5.4 Jambe droite (au premier plan, genou + pied visibles) ---
        paintFill.color = Color.parseColor("#8898A8")
        canvas.drawOval(RectF(ox - thighRx, (hipY + kneeY) / 2f - thighRy,
            ox + thighRx, (hipY + kneeY) / 2f + thighRy), paintFill)
        canvas.drawOval(RectF(ox - thighRx, (hipY + kneeY) / 2f - thighRy,
            ox + thighRx, (hipY + kneeY) / 2f + thighRy), paintStroke)
        paintFill.color = Color.parseColor("#9AAAB8")
        canvas.drawCircle(ox, kneeY, unit * 0.2f, paintFill)   // genou
        canvas.drawCircle(ox, kneeY, unit * 0.2f, paintStroke)
        paintFill.color = Color.parseColor("#8090A0")
        canvas.drawOval(RectF(ox - shinRx, (kneeY + ankleY) / 2f - shinRy,
            ox + shinRx, (kneeY + ankleY) / 2f + shinRy), paintFill)
        canvas.drawOval(RectF(ox - shinRx, (kneeY + ankleY) / 2f - shinRy,
            ox + shinRx, (kneeY + ankleY) / 2f + shinRy), paintStroke)
        paintFill.color = Color.parseColor("#7888A0")
        canvas.drawPath(Path().apply {   // pied
            moveTo(ox - shinRx, ankleY); lineTo(ox + shinRx, ankleY)
            lineTo(footEndX, ankleY + unit * 0.12f)
            lineTo(footEndX, ankleY + unit * 0.28f)
            lineTo(ox - shinRx * 0.5f, ankleY + unit * 0.28f); close()
        }, paintFill)

        // --- 5.5 Tête + cou, avec flexions animées ---
        // canvas.save()/restore() permet de faire pivoter (rotate) seulement
        // cette portion du dessin sans affecter le reste du corps.
        canvas.save()
        canvas.translate(ox, neckY)
        canvas.rotate(neckFlex * 0.5f)
        paintFill.color = Color.parseColor("#9AAAB8")
        canvas.drawRect(RectF(-unit * 0.12f, 0f, unit * 0.2f, shoulderY - neckY), paintFill)  // cou
        canvas.save()
        canvas.translate(0f, -(headRy + unit * 0.1f))
        canvas.rotate(headFlex * 0.4f)
        paintFill.color = Color.parseColor("#607080")  // ombre arrière de la tête
        canvas.drawOval(RectF(-headRx, -headRy, headRx * 0.3f, headRy), paintFill)
        paintFill.color = Color.parseColor("#B0C0D0")  // visage
        canvas.drawOval(RectF(-headRx * 0.6f, -headRy, headRx, headRy), paintFill)
        paintStroke.strokeWidth = 2.5f
        canvas.drawPath(Path().apply {   // nez (profil)
            moveTo(headRx, headRy * 0.1f)
            quadTo(headRx + unit * 0.18f, headRy * 0.2f,
                headRx + unit * 0.05f, headRy * 0.45f)
        }, paintStroke)
        paintStroke.strokeWidth = 2f
        paintFill.color = Color.parseColor("#8898A8")
        canvas.drawOval(RectF(-headRx - unit * 0.18f, -unit * 0.18f,   // oreille
            -headRx + unit * 0.06f, unit * 0.22f), paintFill)
        canvas.restore()
        canvas.restore()

        // ════════════════════════════════════════════════════════════════════
        // 6. DESSIN DES BRAS ANIMÉS
        // ════════════════════════════════════════════════════════════════════
        // Chaque bras est dessiné en 2 segments (bras supérieur + avant-bras),
        // chacun peint 2 fois (une couche sombre en dessous, une couche claire
        // par-dessus légèrement plus fine) pour donner un effet de volume/relief.

        // --- 6.1 Bras gauche (orange) ---
        paintArc.color = Color.argb(40, 255, 160, 0)
        canvas.drawArc(RectF(ox - armLen, shoulderY - armLen,
            ox + armLen, shoulderY + armLen), 90f, 180f, false, paintArc)  // arc décoratif

        paintArmL.color = Color.parseColor("#CC7000"); paintArmL.alpha = 180
        drawThickLine(canvas, ox, shoulderY, elbowLx, elbowLy, armRx, paintArmL)        // ombre bras sup.
        paintArmL.alpha = 255; paintArmL.color = Color.parseColor("#FF9F00")
        drawThickLine(canvas, ox, shoulderY, elbowLx, elbowLy, armRx * 0.65f, paintArmL) // couche claire
        canvas.drawLine(ox, shoulderY, elbowLx, elbowLy, paintArmLStroke)

        paintArmL.color = Color.parseColor("#BB6800"); paintArmL.alpha = 180
        drawThickLine(canvas, elbowLx, elbowLy, handLx, handLy, forearmRx, paintArmL)        // ombre avant-bras
        paintArmL.alpha = 255; paintArmL.color = Color.parseColor("#EE9000")
        drawThickLine(canvas, elbowLx, elbowLy, handLx, handLy, forearmRx * 0.65f, paintArmL) // couche claire
        canvas.drawLine(elbowLx, elbowLy, handLx, handLy, paintArmLStroke)

        canvas.drawCircle(ox, shoulderY, unit * 0.17f, paintJointL)     // épaule
        canvas.drawCircle(elbowLx, elbowLy, unit * 0.14f, paintJointL)  // coude
        canvas.drawCircle(handLx, handLy, unit * 0.11f, paintJointL)    // main

        // --- 6.2 Bras droit (cyan) — même principe ---
        paintArc.color = Color.argb(50, 0, 229, 255)
        canvas.drawArc(RectF(ox - armLen, shoulderY - armLen,
            ox + armLen, shoulderY + armLen), -90f, 180f, false, paintArc)

        paintArmR.color = Color.parseColor("#00AACC"); paintArmR.alpha = 200
        drawThickLine(canvas, ox, shoulderY, elbowRx, elbowRy, armRx, paintArmR)
        paintArmR.alpha = 255; paintArmR.color = Color.parseColor("#00C8E8")
        drawThickLine(canvas, ox, shoulderY, elbowRx, elbowRy, armRx * 0.65f, paintArmR)
        canvas.drawLine(ox, shoulderY, elbowRx, elbowRy, paintArmRStroke)

        paintArmR.color = Color.parseColor("#009EC0"); paintArmR.alpha = 200
        drawThickLine(canvas, elbowRx, elbowRy, handRx, handRy, forearmRx, paintArmR)
        paintArmR.alpha = 255; paintArmR.color = Color.parseColor("#00C0DC")
        drawThickLine(canvas, elbowRx, elbowRy, handRx, handRy, forearmRx * 0.65f, paintArmR)
        canvas.drawLine(elbowRx, elbowRy, handRx, handRy, paintArmRStroke)

        canvas.drawCircle(ox, shoulderY, unit * 0.18f, paintJointR)
        canvas.drawCircle(elbowRx, elbowRy, unit * 0.15f, paintJointR)
        canvas.drawCircle(handRx, handRy, unit * 0.12f, paintJointR)

        // ════════════════════════════════════════════════════════════════════
        // 7. REPÈRES CAPTEURS (ronds + étiquettes reliés par des pointillés)
        // ════════════════════════════════════════════════════════════════════
        // Affiche un petit marqueur à l'endroit physique de chaque capteur sur
        // le bonhomme, relié par une ligne pointillée à son étiquette de texte.
        // Les positions des poignets suivent les vraies coordonnées calculées
        // ci-dessus (handLx/handLy, handRx/handRy) pour rester collées au bras
        // animé ; les autres capteurs ont une position approximative fixe.

        val sensorPositions = listOf(
            "tete"      to Pair(ox - headRx * 0.1f,    headCy - headRy * 0.5f),
            "nuque"     to Pair(ox + unit * 0.05f,      neckY + unit * 0.55f),
            "epaule_d"  to Pair(ox + torsoRx * 0.75f,  shoulderY + unit * 0.35f),
            "epaule_g"  to Pair(ox - torsoRx * 0.1f,   shoulderY + unit * 0.6f),
            "poignet_d" to Pair(handRx,                handRy - unit * 0.1f),
            "poignet_g" to Pair(handLx,                handLy - unit * 0.1f),
        )

        val sensorRadius = unit * 0.22f
        val labelSize = h * 0.026f
        paintSensorLabel.textSize = labelSize
        val labelX = w * 0.72f                       // colonne où s'alignent toutes les étiquettes
        val minLabelSpacing = labelSize + 10f         // espacement minimal vertical entre 2 étiquettes

        // --- 7.1 Anti-collision des étiquettes ---
        // Si deux capteurs sont proches en hauteur (ex: nuque et épaules),
        // leurs étiquettes de texte se chevaucheraient. On les trie par
        // position verticale et on impose un espacement minimal entre elles.
        val labelYMap = mutableMapOf<String, Float>()
        val sorted = sensorPositions.sortedBy { it.second.second }
        var lastLabelY = -999f
        sorted.forEach { (name, pos) ->
            val rawY = pos.second
            val ly = if (rawY - lastLabelY < minLabelSpacing) lastLabelY + minLabelSpacing else rawY
            labelYMap[name] = ly
            lastLabelY = ly
        }

        // --- 7.2 Dessin de chaque repère + son étiquette ---
        sensorPositions.forEach { (name, pos) ->
            val (sx, sy) = pos
            var color = sensorColors[name] ?: Color.WHITE

            // Halo + rond plein + contour blanc
            paintSensor.color = color; paintSensor.alpha = 40
            canvas.drawCircle(sx, sy, sensorRadius * 1.6f, paintSensor)
            paintSensor.alpha = 220
            canvas.drawCircle(sx, sy, sensorRadius, paintSensor)
            canvas.drawCircle(sx, sy, sensorRadius, paintSensorStroke)

            // Petite croix au centre du repère (symbole "capteur")
            val cr = sensorRadius * 0.45f
            canvas.drawLine(sx - cr, sy, sx + cr, sy, paintCross)
            canvas.drawLine(sx, sy - cr, sx, sy + cr, paintCross)

            // Ligne pointillée reliant le capteur à son étiquette de texte
            val labelY = labelYMap[name] ?: sy
            canvas.drawLine(sx + sensorRadius, sy, labelX - 8f, labelY, paintSensorLine)

            // Texte de l'étiquette (nom du capteur en français)
            val labelStr = when (name) {
                "tete"      -> "tête"
                "nuque"     -> "nuque"
                "epaule_g"  -> "épaule G"
                "epaule_d"  -> "épaule D"
                "poignet_g" -> "poignet G"
                "poignet_d" -> "poignet D"
                else        -> name
            }
            val tw = paintSensorLabel.measureText(labelStr)
            val bgPaint = Paint().apply {
                color = Color.argb(140, 10, 10, 30); style = Paint.Style.FILL
            }
            canvas.drawRoundRect(   // fond semi-transparent derrière le texte
                RectF(labelX - 4f, labelY - labelSize - 2f, labelX + tw + 24f, labelY + 6f),
                4f, 4f, bgPaint
            )
            paintSensor.color = color; paintSensor.alpha = 255
            canvas.drawCircle(labelX + 6f, labelY - labelSize * 0.3f, 5f, paintSensor)  // puce couleur
            paintSensorLabel.color = Color.WHITE
            canvas.drawText(labelStr, labelX + 16f, labelY, paintSensorLabel)
        }

        // ════════════════════════════════════════════════════════════════════
        // 8. PANNEAU D'INFO NUMÉRIQUE (encadré élévation / flexion)
        // ════════════════════════════════════════════════════════════════════
        // Affiche les 4 valeurs d'angle en gros chiffres, utile pour le debug
        // et la calibration sur le terrain.

        val infoX = w * 0.75f
        val infoY = h * 0.70f   // position verticale du panneau (plus haut = plus petit %)
        val infoW = w * 0.22f
        val infoH = h * 0.28f
        canvas.drawRoundRect(RectF(infoX, infoY, infoX + infoW, infoY + infoH), 10f, 10f, paintInfo)

        paintLabel.textSize = h * 0.018f

        // Fonction locale : dessine une ligne "étiquette" + "valeur" du panneau
        fun drawInfoLine(label: String, value: String, color: Int, yOff: Float) {
            paintLabel.color = Color.argb(130, 255, 255, 255)
            canvas.drawText(label, infoX + 8f, infoY + yOff, paintLabel)
            paintValue.textSize = h * 0.026f; paintValue.color = color
            canvas.drawText(value, infoX + 8f, infoY + yOff + h * 0.034f, paintValue)
        }
        drawInfoLine("élév. D",  "%.0f°".format(elevD),  Color.parseColor("#00E5FF"), h * 0.028f)
        drawInfoLine("flex. D",  "%.0f°".format(flexD),  Color.parseColor("#69FF47"), h * 0.098f)
        drawInfoLine("élév. G",  "%.0f°".format(elevG),  Color.parseColor("#FFBB33"), h * 0.168f)
        drawInfoLine("flex. G",  "%.0f°".format(flexG),  Color.parseColor("#FF6B6B"), h * 0.238f)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 9. FONCTION UTILITAIRE : dessine un segment "épais" (rectangle arrondi
    //    orienté), utilisée pour donner du volume aux bras plutôt que de
    //    simples lignes fines.
    // ────────────────────────────────────────────────────────────────────────
    private fun drawThickLine(
        canvas: Canvas, x1: Float, y1: Float,
        x2: Float, y2: Float, halfWidth: Float, paint: Paint
    ) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return  // évite une division par zéro / segment de longueur nulle

        // On calcule l'angle du segment, puis on tourne le système de
        // coordonnées pour dessiner un simple rectangle horizontal qui,
        // une fois tourné, correspond exactement au segment voulu.
        val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        canvas.save()
        canvas.translate(x1, y1)
        canvas.rotate(angleDeg)
        canvas.drawRoundRect(
            RectF(-halfWidth * 0.3f, -halfWidth, len + halfWidth * 0.3f, halfWidth),
            halfWidth, halfWidth, paint
        )
        canvas.restore()
    }
}


// ════════════════════════════════════════════════════════════════════════════
//
//   FRAGMENT 3
//   -----------
//   Écran "posture" de l'application. Reçoit les trames Bluetooth brutes via
//   le SharedViewModel, les découpe par préfixe de capteur (PG/EG/PD/ED/TE/NU),
//   et transmet les positions à PostureView pour affichage.
//
// ════════════════════════════════════════════════════════════════════════════
class Fragment3 : Fragment() {

    private lateinit var sharedViewModel: SharedViewModel

    // Mémorise la dernière position connue de chaque capteur (index 0 à 5).
    // Utile si un capteur est absent d'une trame donnée : on garde sa
    // dernière valeur plutôt que de le faire retomber brutalement à zéro.
    private val dernieresPositions = arrayOfNulls<Triple<Float, Float, Float>>(6)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vue = inflater.inflate(R.layout.fragment_3, container, false)

        val postureView = vue.findViewById<PostureView>(R.id.postureView)
        val tvStatus    = vue.findViewById<TextView>(R.id.tvStatus3)

        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        // ────────────────────────────────────────────────────────────────────
        // Observateur de trame Bluetooth
        // ────────────────────────────────────────────────────────────────────
        // Format attendu d'une trame : "TAG,rx,ry,rz,act,posX,posY,posZ,TAG,..."
        // - TAG ∈ {PG, EG, PD, ED, TE, NU} identifie le capteur
        // - posY contient en réalité l'angle "pitch" (en degrés), pas une
        //   vraie position spatiale (voir commentaire firmware ESP32)
        sharedViewModel.codeBarre.observe(viewLifecycleOwner) { raw ->
            if (raw.isNullOrBlank()) return@observe

            val tokens = raw.trim().split(",")

            // Associe chaque préfixe texte à un index de tableau (0 à 5)
            val prefixToIndex = mapOf(
                "PG" to 0, "EG" to 1, "PD" to 2, "ED" to 3, "TE" to 4, "NU" to 5
            )

            var capteursTrouves = 0
            var i = 0
            // Parcourt la trame token par token, à la recherche des préfixes connus.
            // Approche "tolérante" : si un préfixe est trouvé avec assez de
            // valeurs derrière lui, on l'extrait ; sinon on avance d'un cran
            // pour ne pas planter sur une trame partiellement corrompue.
            while (i < tokens.size) {
                val tok = tokens[i].trim()
                val capteurIndex = prefixToIndex[tok]
                if (capteurIndex != null && i + 7 <= tokens.size) {
                    val posX = tokens[i + 5].trim().toFloatOrNull() ?: 0f
                    val posY = tokens[i + 6].trim().toFloatOrNull() ?: 0f
                    val posZ = tokens[i + 7].trim().toFloatOrNull() ?: 0f
                    dernieresPositions[capteurIndex] = Triple(posX, posY, posZ)
                    capteursTrouves++
                    i += 8   // saute le préfixe + les 7 valeurs de ce capteur
                } else {
                    i++      // token non reconnu, on avance d'une case
                }
            }

            if (capteursTrouves > 0) {
                // Transmet les positions (connues ou dernières mémorisées) à la vue
                postureView.setAll(
                    dernieresPositions[0] ?: Triple(0f, 0f, 0f),  // poignet_g
                    dernieresPositions[1] ?: Triple(0f, 0f, 0f),  // epaule_g
                    dernieresPositions[2] ?: Triple(0f, 0f, 0f),  // poignet_d
                    dernieresPositions[3] ?: Triple(0f, 0f, 0f),  // epaule_d
                    dernieresPositions[4] ?: Triple(0f, 0f, 0f),  // tete
                    dernieresPositions[5] ?: Triple(0f, 0f, 0f)   // nuque
                )
                tvStatus.text = "ESP32 connecté ✓ — $capteursTrouves/6 capteurs"
                tvStatus.setTextColor(Color.parseColor("#69FF47"))
            } else {
                tvStatus.text = "Trame reçue, aucun capteur reconnu"
                tvStatus.setTextColor(Color.parseColor("#FF6D00"))
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // Navigation entre les 3 pages de l'application
        // ────────────────────────────────────────────────────────────────────
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