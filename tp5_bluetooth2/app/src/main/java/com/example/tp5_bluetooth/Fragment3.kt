package com.example.tp5_bluetooth

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
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
//   Vue Canvas personnalisée qui dessine une silhouette humaine animée.
//   Elle reçoit les angles des 6 capteurs (tête, nuque, épaules, poignets)
//   et anime le bonhomme en conséquence.
//
//   Principe général :
//   1. setAll() reçoit les nouvelles valeurs de capteurs et redessine
//   2. onDraw() calcule la géométrie (positions des articulations) et peint
//
//   ROTATION 3D :
//   Tous les points anatomiques sont définis en coordonnées 3D normalisées
//   (x=latéral, y=vertical, z=profondeur), puis projetés sur l'écran via
//   une rotation autour de l'axe Y vertical d'angle viewAngleDeg.
//
//   Formule de projection :
//     Xscreen = x3d * cos(A) + z3d * sin(A)
//     Yscreen = -y3d
//
//   Le swipe horizontal (gauche/droite) fait tourner le bonhomme sur lui-même.
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
    private var epauleDRoll  = 0f   // rotX épaule droite = élévation latérale
    private var epauleGRoll  = 0f   // rotX épaule gauche = élévation latérale
    private var poignetDRoll = 0f
    private var poignetGRoll = 0f
    private var poignetDz = 0f
    private var poignetGz = 0f
    private var biasFlexD = 0f   // offset de repos du bras droit
    private var biasFlexG = 0f   // offset de repos du bras gauche
    private var calibre   = false
    private var epauleDz = 0f
    private var epauleGz = 0f
    private var elevGMax = 0f
    private var elevDMax = 0f
    //private var biasTete = 0f
    private var premiereTrameRecue = false

    // ────────────────────────────────────────────────────────────────────────
    // 1b. ANGLE DE VUE (rotation autour de l'axe Y)
    // ────────────────────────────────────────────────────────────────────────
    // 0°   = vue de face
    // 90°  = vue de profil droit (valeur par défaut)
    // 180° = vue de dos
    // 270° = vue de profil gauche
    private var viewAngleDeg = 90f
    private var lastTouchX   = 0f

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
        teteY     = tete.first
        nuqueY    = nuque.second
        epauleDRoll  = epauleD.first
        epauleGRoll  = epauleG.first
        poignetDRoll = poignetD.first
        poignetGRoll = poignetG.first
        poignetDz = poignetD.third
        poignetGz = poignetG.third
        epauleDz = epauleD.third
        epauleGz = epauleG.third

        if (!premiereTrameRecue) {
            biasTete = teteY
            premiereTrameRecue = true
        }

        invalidate()  // redemande à Android d'appeler onDraw()
    }

    private var biasTete = 0f
    private var biasNuque = 0f

    fun calibrer() {
        biasFlexD = poignetDy - epauleDy
        biasFlexG = epauleGy  - poignetGy
        biasTete  = teteY
        biasNuque = nuqueY
        calibre   = true
        android.util.Log.d("CALIBRATION", "teteY=$teteY biasTete=$biasTete nuqueY=$nuqueY biasNuque=$biasNuque")
        invalidate()
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1c. GESTION DU SWIPE TACTILE
    // ────────────────────────────────────────────────────────────────────────
    // Un glissement horizontal fait tourner le bonhomme : dx pixels de swipe
    // → dx * 0.45° de rotation. requestDisallowInterceptTouchEvent empêche
    // le layout parent de voler l'événement tactile pendant le glissement.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                viewAngleDeg = ((viewAngleDeg - dx * 0.45f) % 360f + 360f) % 360f
                lastTouchX = event.x
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
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
    // 3. STRUCTURES 3D ET PROJECTION
    // ────────────────────────────────────────────────────────────────────────

    // Point 3D normalisé (unité = demi-hauteur du corps ≈ 1.0)
    private data class Pt3(val x: Float, val y: Float, val z: Float)
    // Point 2D projeté (coordonnées écran en pixels)
    private data class Pt2(val x: Float, val y: Float)

    /**
     * Projette un point 3D en 2D par rotation autour de l'axe Y.
     * @param pt  point 3D normalisé
     * @param rad angle de vue en radians
     * @param cx  centre horizontal de l'écran (pixels)
     * @param cy  centre vertical correspondant à y3d=0 (pixels)
     * @param sc  facteur d'échelle : 1 unité 3D → sc pixels
     */
    private fun proj(pt: Pt3, rad: Float, cx: Float, cy: Float, sc: Float): Pt2 {
        val xs = pt.x * cos(rad) + pt.z * sin(rad)
        val ys = -pt.y  // y3d positif = vers le haut → inversé sur l'écran
        return Pt2(cx + xs * sc, cy + ys * sc)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. CALCUL DES BRAS EN 3D
    // ────────────────────────────────────────────────────────────────────────
    // Le bras supérieur tourne dans le plan sagittal autour de l'épaule.
    // L'avant-bras prolonge dans la même direction, avec la flexion du coude.
    // side = +1 pour bras droit (épaule en x=+0.28)
    // side = -1 pour bras gauche (épaule en x=-0.28)
    private data class ArmPts(val shoulder: Pt3, val elbow: Pt3, val hand: Pt3)

    private fun computeArm(elevDeg: Float, flexDeg: Float, rollDeg: Float, side: Float): ArmPts {
        val ARM_LEN  = 0.33f
        val FORE_LEN = 0.30f
        val shoulderX = side * 0.28f
        val shoulderY = 0.52f

        val eR = Math.toRadians(elevDeg.toDouble()).toFloat()
        val fR = Math.toRadians(flexDeg.toDouble()).toFloat()

        // Direction bras supérieur dans le plan sagittal
        // elev=0°  → bras pendant le long du corps (uy=-1, uz=0)
        // elev=90° → bras tendu vers l'avant        (uy=0,  uz=1)
        val rR = Math.toRadians(rollDeg.toDouble()).toFloat()
        val uy = -cos(eR)
        val uz =  sin(eR)
        val elbX = shoulderX + sin(rR) * ARM_LEN * side
        val elbY = shoulderY + uy * ARM_LEN
        val elbZ =             uz * ARM_LEN
        val fuy = -cos(eR + fR)
        val fuz =  sin(eR + fR)
        val handX = elbX + sin(rR) * FORE_LEN * side
        val handY = elbY + fuy * FORE_LEN
        val handZ = elbZ + fuz * FORE_LEN

        return ArmPts(
            Pt3(shoulderX, shoulderY, 0f),
            Pt3(elbX, elbY, elbZ),
            Pt3(handX, handY, handZ)
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. DESSIN PRINCIPAL — appelé automatiquement par Android à chaque
    //    rafraîchissement (déclenché par invalidate() dans setAll() ou
    //    par le swipe tactile)
    // ────────────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0A0A1E"))  // fond bleu nuit

        val w = width.toFloat()
        val h = height.toFloat()

        // --- 5.1 Grille de fond (repères visuels, purement esthétique) ---
        var gx = 0f; while (gx <= w) { canvas.drawLine(gx, 0f, gx, h, paintGrid); gx += 60f }
        var gy = 0f; while (gy <= h) { canvas.drawLine(0f, gy, w, gy, paintGrid); gy += 60f }

        // Paramètres de projection 3D → 2D
        val rad = Math.toRadians(viewAngleDeg.toDouble()).toFloat()
        val cx  = w * 0.48f   // centre horizontal (légèrement décalé pour les étiquettes)
        val cy  = h * 0.45f   // centre vertical (y3d=0 correspond au milieu du corps)
        val sc  = h * 0.40f   // échelle : 1 unité 3D = sc pixels

        // Raccourci local pour projeter un point 3D
        fun p(pt: Pt3) = proj(pt, rad, cx, cy, sc)

        // ── Angles capteurs → angles d'animation ─────────────────────────
        // Identiques à l'ancienne version (signes calibrés empiriquement)
        val elevD = epauleDy
        val elevGRaw = -epauleGy
        if (elevGRaw < elevGMax - 15f) elevGMax = elevGRaw
        else if (elevGRaw > elevGMax) elevGMax = elevGRaw
        val elevG = elevGMax.coerceIn(-90f, 88f)
        val flexD = (poignetDRoll - epauleDRoll).coerceIn(0f, 150f) * (1f - elevD.coerceIn(0f, 88f) / 88f)
        val flexG = (epauleGy - poignetGy - biasFlexG).coerceIn(0f, 150f)
        val headFlex = 0f
        val neckFlex = 0f

        // ── Géométrie des bras en 3D ──────────────────────────────────────
        val armD = computeArm(elevD, flexD, 0f, +1f)
        val armG = computeArm(elevG, flexG, 0f, -1f)

        // Projection des bras en 2D
        val pShD   = p(armD.shoulder); val pElbD = p(armD.elbow); val pHandD = p(armD.hand)
        val pShG   = p(armG.shoulder); val pElbG = p(armG.elbow); val pHandG = p(armG.hand)

        // ── Points anatomiques fixes en 3D ────────────────────────────────
        // Torse
        val torsoTL   = Pt3(-0.26f,  0.52f, 0f)
        val torsoTR   = Pt3( 0.26f,  0.52f, 0f)
        val torsoBL   = Pt3(-0.20f, -0.22f, 0f)
        val torsoBR   = Pt3( 0.20f, -0.22f, 0f)
        val spineTop  = Pt3( 0f,     0.52f, 0f)
        val spineBot  = Pt3( 0f,    -0.22f, 0f)
        // Bassin
        val hipCBot   = Pt3( 0f,    -0.30f, 0f)
        // Hanches
        val hipDPt    = Pt3( 0.16f, -0.22f, 0f)
        val hipGPt    = Pt3(-0.16f, -0.22f, 0f)
        // Genoux
        val kneeDPt   = Pt3( 0.14f, -0.72f, 0f)
        val kneeGPt   = Pt3(-0.14f, -0.72f, 0f)
        // Chevilles
        val ankleDPt  = Pt3( 0.12f, -1.12f, 0f)
        val ankleGPt  = Pt3(-0.12f, -1.12f, 0f)
        // Pieds (s'étendent vers l'avant en z+)
        val footDToe  = Pt3( 0.13f, -1.18f,  0.28f)
        val footGToe  = Pt3(-0.13f, -1.18f,  0.28f)
        val footDHeel = Pt3( 0.13f, -1.18f, -0.08f)
        val footGHeel = Pt3(-0.13f, -1.18f, -0.08f)
        // Cou / Tête
        val neckPt    = Pt3(0f, 0.62f, 0f)
        val headFlexR = Math.toRadians(headFlex.toDouble()).toFloat()
        val headTopPt = Pt3(0f, 0.62f + cos(headFlexR) * 0.30f, sin(headFlexR) * 0.30f)

        // Projection de tous les points fixes
        val pTTL    = p(torsoTL);  val pTTR    = p(torsoTR)
        val pTBL    = p(torsoBL);  val pTBR    = p(torsoBR)
        val pSpT    = p(spineTop); val pSpB    = p(spineBot)
        val pHipBot = p(hipCBot)
        val pHipD   = p(hipDPt);   val pHipG   = p(hipGPt)
        val pKneeD  = p(kneeDPt);  val pKneeG  = p(kneeGPt)
        val pAnkD   = p(ankleDPt); val pAnkG   = p(ankleGPt)
        val pFtDToe = p(footDToe); val pFtGToe = p(footGToe)
        val pFtDHeel= p(footDHeel);val pFtGHeel= p(footGHeel)
        val pNeck   = p(neckPt);   val pHeadTop= p(headTopPt)

        // ── Profondeur projetée pour l'ordre avant/arrière-plan ───────────
        // depth = composante Z après rotation = z*cos(rad) - x*sin(rad)
        // La valeur la plus grande = élément le plus "devant" → dessiné en dernier
        fun depth(pt: Pt3) = pt.z * cos(rad) - pt.x * sin(rad)

        val armDFront = depth(armD.elbow) < depth(armG.elbow)
        val legDFront = depth(kneeDPt)    >= depth(kneeGPt)

        // ── Calcul de la tête (centre + demi-axes de l'ellipse) ──────────
        val headCX = (pHeadTop.x + pNeck.x) * 0.5f
        val headCY = (pHeadTop.y + pNeck.y) * 0.5f
        val headRY = sqrt((pHeadTop.x - pNeck.x).pow(2) + (pHeadTop.y - pNeck.y).pow(2)) * 0.55f
        val headRX = sc * 0.12f * (0.32f + 0.68f * abs(cos(rad)))

        // ════════════════════════════════════════════════════════════════════
        // 6. ORDRE DE DESSIN : arrière-plan → corps → avant-plan
        // ════════════════════════════════════════════════════════════════════

        // --- 6.1 Jambe arrière-plan ---
        if (legDFront) drawLeg(canvas, pHipG, pKneeG, pAnkG, pFtGToe, pFtGHeel, "#2f4055", "#253444", "#354A60", 0.5f, sc)
        else           drawLeg(canvas, pHipD, pKneeD, pAnkD, pFtDToe, pFtDHeel, "#2f4055", "#253444", "#354A60", 0.5f, sc)

        // --- 6.2 Bras arrière-plan ---
        if (armDFront) {
            paintArmL.color = Color.parseColor("#BB6800"); paintArmL.alpha = 90
            drawThickLine(canvas, pShG.x, pShG.y, pElbG.x, pElbG.y, sc * 0.042f, paintArmL)
            drawThickLine(canvas, pElbG.x, pElbG.y, pHandG.x, pHandG.y, sc * 0.034f, paintArmL)
            paintJointL.alpha = 90
            canvas.drawCircle(pShG.x,   pShG.y,   sc * 0.055f, paintJointL)
            canvas.drawCircle(pElbG.x,  pElbG.y,  sc * 0.044f, paintJointL)
            canvas.drawCircle(pHandG.x, pHandG.y, sc * 0.034f, paintJointL)
            paintArmL.alpha = 255; paintJointL.alpha = 255
        } else {
            paintArmR.color = Color.parseColor("#007590"); paintArmR.alpha = 90
            drawThickLine(canvas, pShD.x, pShD.y, pElbD.x, pElbD.y, sc * 0.042f, paintArmR)
            drawThickLine(canvas, pElbD.x, pElbD.y, pHandD.x, pHandD.y, sc * 0.034f, paintArmR)
            paintJointR.alpha = 90
            canvas.drawCircle(pShD.x,   pShD.y,   sc * 0.055f, paintJointR)
            canvas.drawCircle(pElbD.x,  pElbD.y,  sc * 0.044f, paintJointR)
            canvas.drawCircle(pHandD.x, pHandD.y, sc * 0.034f, paintJointR)
            paintArmR.alpha = 255; paintJointR.alpha = 255
        }

        // --- 6.3 Torse ---
        paintFill.color = Color.parseColor("#7080A0")
        canvas.drawPath(Path().apply {
            moveTo(pTTL.x, pTTL.y); lineTo(pTTR.x, pTTR.y)
            lineTo(pTBR.x, pTBR.y); lineTo(pTBL.x, pTBL.y); close()
        }, paintFill)
        // Ombre latérale sur la moitié gauche du torse
        paintFill.color = Color.argb(60, 0, 0, 0)
        val midTorsoT = p(Pt3(-0.26f, 0.52f, 0f)); val midTorsoB = p(Pt3(-0.26f, -0.22f, 0f))
        val midCenterT= p(Pt3(0f,     0.52f, 0f)); val midCenterB= p(Pt3(0f,     -0.22f, 0f))
        canvas.drawPath(Path().apply {
            moveTo(midTorsoT.x, midTorsoT.y); lineTo(midCenterT.x, midCenterT.y)
            lineTo(midCenterB.x, midCenterB.y); lineTo(midTorsoB.x, midTorsoB.y); close()
        }, paintFill)
        // Contour torse
        paintStroke.color = Color.parseColor("#8090A8"); paintStroke.strokeWidth = 1.2f
        canvas.drawPath(Path().apply {
            moveTo(pTTL.x, pTTL.y); lineTo(pTTR.x, pTTR.y)
            lineTo(pTBR.x, pTBR.y); lineTo(pTBL.x, pTBL.y); close()
        }, paintStroke)
        // Colonne vertébrale (ligne pointillée décorative)
        canvas.drawLine(pSpT.x, pSpT.y, pSpB.x, pSpB.y, paintSpine)

        // --- 6.4 Bassin ---
        paintFill.color = Color.parseColor("#607090")
        canvas.drawPath(Path().apply {
            moveTo(pTBL.x, pTBL.y); lineTo(pTBR.x, pTBR.y)
            quadTo(pTBR.x + 6f, pHipBot.y + 12f, pHipBot.x, pHipBot.y + 14f)
            quadTo(pTBL.x - 6f, pHipBot.y + 12f, pTBL.x, pTBL.y); close()
        }, paintFill)

        // --- 6.5 Tête + cou, avec flexions animées ---
        // Ombre arrière de la tête
        paintFill.color = Color.parseColor("#3D5060")
        canvas.drawOval(RectF(
            headCX - cos(rad) * headRX * 0.3f - headRX * 0.85f, headCY - headRY,
            headCX - cos(rad) * headRX * 0.3f + headRX * 0.85f, headCY + headRY
        ), paintFill)
        // Cou
        paintFill.color = Color.parseColor("#9AAAB8")
        drawThickLine(canvas, pNeck.x, pNeck.y, pNeck.x, pNeck.y + sc * 0.14f, sc * 0.06f, paintFill)
        // Visage
        paintFill.color = Color.parseColor("#B0C0D0")
        canvas.drawOval(RectF(
            headCX + cos(rad) * headRX * 0.15f - headRX, headCY - headRY,
            headCX + cos(rad) * headRX * 0.15f + headRX, headCY + headRY
        ), paintFill)
        // Nez (visible en profil droit, cos(rad) < 0)
        val noseVis = -cos(rad)
        if (noseVis > 0.15f) {
            paintStroke.color = Color.argb((min(1f, noseVis * 1.4f) * 180).toInt(), 96, 112, 128)
            paintStroke.strokeWidth = 2.5f
            canvas.drawPath(Path().apply {
                moveTo(headCX + headRX * 0.85f, headCY + headRY * 0.12f)
                lineTo(headCX + headRX * 1.20f, headCY + headRY * 0.28f)
                lineTo(headCX + headRX * 1.00f, headCY + headRY * 0.42f)
            }, paintStroke)
        }
        // Oreille (visible en profil, abs(cos) > 0.25)
        val earVis = abs(cos(rad))
        if (earVis > 0.25f) {
            val earSide = if (cos(rad) < 0f) -1f else 1f
            paintFill.color = Color.parseColor("#7888A0")
            paintFill.alpha = (min(1f, earVis * 1.2f) * 255).toInt()
            canvas.drawOval(RectF(
                headCX - earSide * headRX - headRX * 0.18f, headCY - headRY * 0.30f,
                headCX - earSide * headRX + headRX * 0.18f, headCY + headRY * 0.30f
            ), paintFill)
            paintFill.alpha = 255
        }

        // --- 6.6 Jambe avant-plan ---
        if (legDFront) drawLeg(canvas, pHipD, pKneeD, pAnkD, pFtDToe, pFtDHeel, "#8090A8", "#6878A0", "#9AAAB8", 1f, sc)
        else           drawLeg(canvas, pHipG, pKneeG, pAnkG, pFtGToe, pFtGHeel, "#8090A8", "#6878A0", "#9AAAB8", 1f, sc)

        // --- 6.7 Bras avant-plan ---
        if (armDFront) {
            // Bras droit (cyan) au premier plan
            paintArmR.color = Color.parseColor("#00AACC"); paintArmR.alpha = 200
            drawThickLine(canvas, pShD.x, pShD.y, pElbD.x, pElbD.y, sc * 0.042f, paintArmR)
            paintArmR.alpha = 255; paintArmR.color = Color.parseColor("#00C8E8")
            drawThickLine(canvas, pShD.x, pShD.y, pElbD.x, pElbD.y, sc * 0.028f, paintArmR)
            canvas.drawLine(pShD.x, pShD.y, pElbD.x, pElbD.y, paintArmRStroke)
            paintArmR.color = Color.parseColor("#009EC0"); paintArmR.alpha = 200
            drawThickLine(canvas, pElbD.x, pElbD.y, pHandD.x, pHandD.y, sc * 0.034f, paintArmR)
            paintArmR.alpha = 255; paintArmR.color = Color.parseColor("#00C0DC")
            drawThickLine(canvas, pElbD.x, pElbD.y, pHandD.x, pHandD.y, sc * 0.022f, paintArmR)
            canvas.drawLine(pElbD.x, pElbD.y, pHandD.x, pHandD.y, paintArmRStroke)
            canvas.drawCircle(pShD.x,   pShD.y,   sc * 0.055f, paintJointR)
            canvas.drawCircle(pElbD.x,  pElbD.y,  sc * 0.044f, paintJointR)
            canvas.drawCircle(pHandD.x, pHandD.y, sc * 0.034f, paintJointR)
        } else {
            // Bras gauche (orange) au premier plan
            paintArmL.color = Color.parseColor("#CC7000"); paintArmL.alpha = 180
            drawThickLine(canvas, pShG.x, pShG.y, pElbG.x, pElbG.y, sc * 0.042f, paintArmL)
            paintArmL.alpha = 255; paintArmL.color = Color.parseColor("#FF9F00")
            drawThickLine(canvas, pShG.x, pShG.y, pElbG.x, pElbG.y, sc * 0.028f, paintArmL)
            canvas.drawLine(pShG.x, pShG.y, pElbG.x, pElbG.y, paintArmLStroke)
            paintArmL.color = Color.parseColor("#BB6800"); paintArmL.alpha = 180
            drawThickLine(canvas, pElbG.x, pElbG.y, pHandG.x, pHandG.y, sc * 0.034f, paintArmL)
            paintArmL.alpha = 255; paintArmL.color = Color.parseColor("#EE9000")
            drawThickLine(canvas, pElbG.x, pElbG.y, pHandG.x, pHandG.y, sc * 0.022f, paintArmL)
            canvas.drawLine(pElbG.x, pElbG.y, pHandG.x, pHandG.y, paintArmLStroke)
            canvas.drawCircle(pShG.x,   pShG.y,   sc * 0.055f, paintJointL)
            canvas.drawCircle(pElbG.x,  pElbG.y,  sc * 0.044f, paintJointL)
            canvas.drawCircle(pHandG.x, pHandG.y, sc * 0.034f, paintJointL)
        }

        // ════════════════════════════════════════════════════════════════════
        // 7. REPÈRES CAPTEURS (ronds + étiquettes reliés par des pointillés)
        // ════════════════════════════════════════════════════════════════════
        // Positions des capteurs : les poignets suivent les coordonnées 2D
        // réelles du bras animé ; les autres sont fixes sur le corps.
        val sensorPositions = listOf(
            "tete"      to Pair(headCX,               headCY - headRY * 0.5f),
            "nuque"     to Pair(pNeck.x + sc * 0.03f, pNeck.y + sc * 0.06f),
            "epaule_d"  to Pair(pShD.x,               pShD.y),
            "epaule_g"  to Pair(pShG.x,               pShG.y),
            "poignet_d" to Pair(pHandD.x,             pHandD.y),
            "poignet_g" to Pair(pHandG.x,             pHandG.y)
        )

        val sensorRadius  = sc * 0.065f
        val labelSize     = h * 0.026f
        paintSensorLabel.textSize = labelSize
        val labelX        = w * 0.68f         // colonne où s'alignent toutes les étiquettes
        val minLabelSpacing = labelSize + 10f  // espacement minimal vertical entre 2 étiquettes

        // --- 7.1 Anti-collision des étiquettes ---
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
            val bgPaint = Paint()
            bgPaint.color = Color.argb(140, 10, 10, 30)
            bgPaint.style = Paint.Style.FILL

            canvas.drawRoundRect(
                RectF(labelX - 4f, labelY - labelSize - 2f, labelX + tw + 24f, labelY + 6f),
                4f, 4f, bgPaint
            )
            paintSensor.color = color; paintSensor.alpha = 255
            canvas.drawCircle(labelX + 6f, labelY - labelSize * 0.3f, 5f, paintSensor)
            paintSensorLabel.color = color
            canvas.drawText(labelStr, labelX + 16f, labelY, paintSensorLabel)
        }

        // ════════════════════════════════════════════════════════════════════
        // 8. PANNEAU D'INFO NUMÉRIQUE (encadré élévation / flexion)
        // ════════════════════════════════════════════════════════════════════
        val infoX = w * 0.02f
        val infoY = h * 0.53f
        val infoW = w * 0.22f
        val infoH = h * 0.42f
        canvas.drawRoundRect(RectF(infoX, infoY, infoX + infoW, infoY + infoH), 10f, 10f, paintInfo)

        paintLabel.textSize = h * 0.018f
        fun drawInfoLine(label: String, value: String, color: Int, yOff: Float) {
            paintLabel.color = Color.argb(130, 255, 255, 255)
            canvas.drawText(label, infoX + 8f, infoY + yOff, paintLabel)
            paintValue.textSize = h * 0.026f; paintValue.color = color
            canvas.drawText(value, infoX + 8f, infoY + yOff + h * 0.034f, paintValue)
        }
        drawInfoLine("élév. D", "%.0f°".format(elevD), Color.parseColor("#00E5FF"), h * 0.028f)
        drawInfoLine("flex. D", "%.0f°".format(flexD), Color.parseColor("#69FF47"), h * 0.098f)
        drawInfoLine("élév. G", "%.0f°".format(elevG), Color.parseColor("#FFBB33"), h * 0.168f)
        drawInfoLine("flex. G", "%.0f°".format(flexG), Color.parseColor("#FF6B6B"), h * 0.238f)
        drawInfoLine("tête", "%.0f°".format(teteY - biasTete),  Color.parseColor("#D070FF"), h * 0.308f)
        drawInfoLine("dos",  "%.0f°".format(nuqueY - biasNuque), Color.parseColor("#FF70C0"), h * 0.378f)

        // ════════════════════════════════════════════════════════════════════
        // 9. BOUSSOLE D'ANGLE DE VUE (petit indicateur en bas à droite)
        // ════════════════════════════════════════════════════════════════════
        val bx = w * 0.88f; val by = h * 0.88f; val br = sc * 0.09f
        paintFill.color = Color.argb(40, 255, 255, 255)
        canvas.drawCircle(bx, by, br, paintFill)
        paintStroke.color = Color.argb(60, 255, 255, 255); paintStroke.strokeWidth = 1f
        canvas.drawCircle(bx, by, br, paintStroke)
        // Aiguille
        val needleRad = Math.toRadians((viewAngleDeg - 90.0)).toFloat()
        paintStroke.color = Color.parseColor("#00E5FF"); paintStroke.strokeWidth = 2f
        canvas.drawLine(bx, by, bx + cos(needleRad) * br * 0.82f, by + sin(needleRad) * br * 0.82f, paintStroke)
        // Labels cardinaux
        val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            textSize = h * 0.020f; textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("F", bx, by - br - 5f, compassPaint)
        canvas.drawText("D", bx, by + br + h * 0.026f, compassPaint)
        compassPaint.color = Color.argb(180, 0, 229, 255)
        canvas.drawText("%.0f°".format(((viewAngleDeg % 360f) + 360f) % 360f), bx, by + h * 0.008f, compassPaint)
        // Hint swipe
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, 255, 255, 255)
            textSize = h * 0.019f; textAlign = Paint.Align.CENTER
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("← glisser pour tourner →", w * 0.48f, h * 0.97f, hintPaint)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 10. FONCTIONS UTILITAIRES DE DESSIN
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Dessine une jambe complète (cuisse → tibia → pied) à partir de points
     * 2D déjà projetés.
     * @param alpha opacité 0f–1f (0.5 pour l'arrière-plan, 1.0 pour l'avant)
     */
    private fun drawLeg(
        canvas: Canvas,
        hip: Pt2, knee: Pt2, ankle: Pt2, toe: Pt2, heel: Pt2,
        colorThigh: String, colorShin: String, colorKnee: String,
        alpha: Float, sc: Float
    ) {
        val r1 = sc * 0.065f; val r2 = sc * 0.050f

        paintFill.color = Color.parseColor(colorThigh)
        paintFill.alpha = (alpha * 255).toInt()
        drawThickLine(canvas, hip.x, hip.y, knee.x, knee.y, r1, paintFill)

        paintFill.color = Color.parseColor(colorShin)
        drawThickLine(canvas, knee.x, knee.y, ankle.x, ankle.y, r2, paintFill)

        // Genou
        paintFill.color = Color.parseColor(colorKnee)
        canvas.drawCircle(knee.x, knee.y, sc * 0.044f, paintFill)

        // Pied
        paintFill.color = Color.parseColor(colorShin)
        canvas.drawPath(Path().apply {
            moveTo(heel.x, heel.y)
            lineTo(ankle.x, ankle.y)
            lineTo(toe.x, toe.y)
            lineTo(toe.x + (toe.x - heel.x) * 0.06f, toe.y + sc * 0.035f)
            lineTo(heel.x, heel.y + sc * 0.035f)
            close()
        }, paintFill)

        paintFill.alpha = 255
    }

    /**
     * Dessine un segment "épais" (rectangle arrondi orienté) entre deux points.
     * Utilisée pour donner du volume aux bras et aux jambes.
     */
    private fun drawThickLine(
        canvas: Canvas, x1: Float, y1: Float,
        x2: Float, y2: Float, halfWidth: Float, paint: Paint
    ) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return  // évite une division par zéro / segment de longueur nulle

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

        // La PostureView doit être cliquable pour recevoir les événements tactiles
        postureView.isClickable = true
        postureView.isFocusable = true

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
                    val rotX = tokens[i + 1].trim().toFloatOrNull() ?: 0f
                    val rotY = tokens[i + 2].trim().toFloatOrNull() ?: 0f
                    val rotZ = tokens[i + 3].trim().toFloatOrNull() ?: 0f
                    dernieresPositions[capteurIndex] = Triple(rotX, rotY, rotZ)
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
        vue.findViewById<Button>(R.id.btnCalibrer).setOnClickListener {
            postureView.calibrer()
        }

        return vue
    }
}