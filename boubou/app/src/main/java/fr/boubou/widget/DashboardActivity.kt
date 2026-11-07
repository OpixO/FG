package fr.boubou.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("boubou", Context.MODE_PRIVATE) }
    private lateinit var panel: LinearLayout
    private val navy = Color.rgb(18, 34, 54)
    private val pale = Color.rgb(220, 234, 246)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        render()
    }
    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(navy) }
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(34, 32, 34, 36) }
        scroll.addView(panel)
        setContentView(scroll)
        text("🌺  BOUBOU ULTIMATE", 26, Color.WHITE)
        text("Saint-Brès · assistant pour ton bougainvillier", 14, pale)
        val updated = prefs.getLong("updated", 0L)
        if (updated > 0) {
            val time = Instant.ofEpochMilli(updated).atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            text("Dernière météo : $time", 13, pale)
        } else text("Météo : en attente de synchronisation", 13, pale)
        text(prefs.getString("summary", "Touche Actualiser pour obtenir les prévisions") ?: "", 20, Color.rgb(135,231,180))
        button("↻  Actualiser la météo") { WeatherWorker.enqueue(this); Toast.makeText(this,"Actualisation demandée",Toast.LENGTH_SHORT).show() }
        heading("🌙  Prévisions des cinq nuits")
        text(prefs.getString("forecast", "Les prévisions apparaîtront après la première mise à jour") ?: "", 16, Color.WHITE)
        heading("🌬️  Vent, rafales et pluie")
        text(prefs.getString("conditions", "En attente de données météo") ?: "", 15, pale)
        heading("🏡  Emplacement de Boubou")
        val inside = prefs.getBoolean("inside", false)
        text(if (inside) "Actuellement : à l'intérieur" else "Actuellement : dehors", 16, Color.WHITE)
        button(if (inside) "Marquer : Boubou est dehors" else "Marquer : Boubou est dedans") {
            prefs.edit().putBoolean("inside", !inside).apply()
            render()
        }
        heading("💧  Arrosage")
        val lastWater = prefs.getLong("water", 0)
        text(if (lastWater == 0L) "Dernier arrosage : non renseigné" else "Dernier arrosage : " + Instant.ofEpochMilli(lastWater).atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 15, pale)
        button("J'ai arrosé Boubou") {
            prefs.edit().putLong("water", System.currentTimeMillis()).apply()
            appendJournal("Arrosage enregistré")
            render()
        }
        text("Conseil : vérifie le terreau avec le doigt à 2–3 cm avant d'arroser. Une prévision météo ne mesure pas son humidité.", 13, pale)
        heading("🌱  Conseil saisonnier")
        val month = java.time.LocalDate.now(ZoneId.of("Europe/Paris")).monthValue
        val advice = when(month) {
            12,1,2 -> "Hiver : privilégie la lumière, limite l'arrosage et protège Boubou des nuits froides."
            3,4,5 -> "Printemps : augmente progressivement l'exposition au soleil et surveille les nouvelles pousses."
            6,7,8 -> "Été : surveille le dessèchement du pot et les fortes chaleurs, surtout en plein soleil."
            else -> "Automne : réduis les arrosages, surveille les minima nocturnes et évite les allers-retours inutiles."
        }
        text(advice, 15, pale)
        heading("📓  Journal de santé")
        button("Ajouter une observation") {
            val field = EditText(this).apply { hint = "Feuilles, fleurs, terreau, croissance…" }
            AlertDialog.Builder(this).setTitle("Nouvelle observation").setView(field).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer") { _, _ ->
                val note = field.text.toString().trim()
                if(note.isNotEmpty()) { appendJournal(note); render() }
            }.show()
        }
        text(prefs.getString("journal", "Aucune observation enregistrée") ?: "", 13, pale)
        heading("📊  Historique des alertes")
        text(prefs.getString("history", "Aucune alerte enregistrée") ?: "", 13, pale)
        text("Données météo : Open-Meteo. Les seuils de prudence ne remplacent pas l'observation de la plante.", 12, pale)
    }
    private fun appendJournal(note: String) {
        val date = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
        val previous = prefs.getString("journal", "") ?: ""
        prefs.edit().putString("journal", "• $date — $note\n" + previous.take(2500)).apply()
    }
    private fun heading(value: String) { text(value, 19, Color.rgb(255, 164, 203)) }
    private fun text(value: String, size: Int, color: Int) {
        panel.addView(TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, 13, 0, 13)
        })
    }
    private fun button(label: String, action: () -> Unit) {
        panel.addView(Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
