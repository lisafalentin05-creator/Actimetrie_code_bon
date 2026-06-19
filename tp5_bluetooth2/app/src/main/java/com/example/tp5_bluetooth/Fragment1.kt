package com.example.tp5_bluetooth

import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class Fragment1 : Fragment() {

    private lateinit var vue: View
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var btnVerifPerms: Button
    private lateinit var btnActivBT: Button
    private lateinit var btnPaired: Button
    private lateinit var btnEffacer: Button
    private lateinit var btnInit: Button
    private lateinit var btnStart: Button
    private lateinit var listView: ListView
    private val listeAppareils = mutableListOf<String>()
    private lateinit var tvCodeBarre: TextView
    private lateinit var sharedViewModel: SharedViewModel

    // Référence au thread connecté pour pouvoir lui envoyer des commandes
    private var connectedThread: ConnectedThread? = null

    private val handler = Handler(Looper.getMainLooper()) { message ->
        val texte = message.obj as String

        when (texte.trim()) {
            "INIT_OK" -> {
                Toast.makeText(activity, "Capteurs initialisés ✓", Toast.LENGTH_SHORT).show()
                btnInit.visibility = View.GONE
                btnStart.visibility = View.VISIBLE
            }
            "START_OK" -> {
                Toast.makeText(activity, "Streaming démarré ✓", Toast.LENGTH_SHORT).show()
                btnStart.visibility = View.GONE
            }
            else -> {
                // Trame de données normale
                tvCodeBarre.text = texte
                tvCodeBarre.visibility = View.VISIBLE
                sharedViewModel.codeBarre.value = texte
            }
        }
        true
    }

    private val requetePermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                btnActivBT.isEnabled = true
                btnPaired.isEnabled  = true
                btnEffacer.isEnabled = true
                Toast.makeText(activity, "Permissions OK", Toast.LENGTH_SHORT).show()
            }
        }

    private val requeteActivationBT =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(activity, "L'utilisateur a activé le BT", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "L'utilisateur a refusé", Toast.LENGTH_SHORT).show()
            }
        }

    class ConnectThread(
        device: BluetoothDevice,
        private val adapter: BluetoothAdapter,
        private val handler: Handler,
        private val onConnected: (ConnectedThread) -> Unit
    ) : Thread() {
        private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val mmSocket: BluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)

        override fun run() {
            adapter.cancelDiscovery()
            try {
                Log.i("BT", "début attente connexion")
                mmSocket.connect()
                Log.i("BT", "fin attente connexion - connexion OK")
                val ct = ConnectedThread(mmSocket, handler)
                onConnected(ct)
                ct.start()
            } catch (e: IOException) {
                Log.e("BT", "Erreur connexion", e)
            }
        }
    }

    class ConnectedThread(private val mmSocket: BluetoothSocket, private val handler: Handler) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        /** Envoie une commande texte à l'ESP32, terminée par un retour à la ligne. */
        fun envoyerCommande(commande: String) {
            try {
                mmOutStream.write((commande + "\n").toByteArray())
                Log.i("BT", "Commande envoyée : $commande")
            } catch (e: IOException) {
                Log.e("BT", "Erreur envoi commande", e)
            }
        }

        override fun run() {
            var numBytes: Int
            var result: String = ""

            while (true) {
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d("BT", "Input stream was disconnected", e)
                    break
                }

                for (i in 0 until numBytes) {
                    val byte = mmBuffer[i].toInt()
                    when {
                        byte == 10 -> {
                            Log.i("BT", "Trame reçue : $result")
                            val message = handler.obtainMessage()
                            message.obj = result
                            handler.sendMessage(message)
                            result = ""
                        }
                        byte == 13 -> { }
                        else -> {
                            result += byte.toChar()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        vue = inflater.inflate(R.layout.fragment_1, container, false)

        btnVerifPerms = vue.findViewById(R.id.buttonpermission)
        btnActivBT    = vue.findViewById(R.id.buttonactivation)
        btnPaired     = vue.findViewById(R.id.btnAppareils)
        btnEffacer    = vue.findViewById(R.id.btnEffacer)
        btnInit       = vue.findViewById(R.id.btnInit)
        btnStart      = vue.findViewById(R.id.btnStart)
        listView      = vue.findViewById(R.id.listViewAppareils)
        tvCodeBarre   = vue.findViewById(R.id.tvCodeBarre)

        btnActivBT.isEnabled = false
        sharedViewModel = ViewModelProvider(requireActivity())[SharedViewModel::class.java]

        sharedViewModel.codeBarre.observe(viewLifecycleOwner) { codeBarre ->
            if (codeBarre != null) {
                tvCodeBarre.text = codeBarre
                tvCodeBarre.visibility = View.VISIBLE
            }
        }

        // Initialisation Bluetooth
        bluetoothManager = activity?.getSystemService(BluetoothManager::class.java)!!
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(activity, "La machine ne possède pas le Bluetooth", Toast.LENGTH_SHORT).show()
        } else {
            btnVerifPerms.isEnabled = true
            Toast.makeText(activity, "Interface BT existe", Toast.LENGTH_SHORT).show()
        }

        // ── Permissions BT ──────────────────────────────────────────────────
        btnVerifPerms.setOnClickListener {
            val btPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
                )
            } else {
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_ADMIN,
                    android.Manifest.permission.BLUETOOTH,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
            requetePermissions.launch(btPermissions)
        }

        // ── Activation BT ───────────────────────────────────────────────────
        btnActivBT.setOnClickListener {
            if (bluetoothAdapter.isEnabled) {
                Toast.makeText(activity, "BT déjà activé", Toast.LENGTH_SHORT).show()
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                requeteActivationBT.launch(enableBtIntent)
            }
        }

        // ── Liste appareils associés ─────────────────────────────────────────
        btnPaired.setOnClickListener {
            listeAppareils.clear()

            if (bluetoothAdapter.isEnabled) {
                bluetoothAdapter.bondedDevices.forEach { device ->
                    listeAppareils.add(device.name + '\n' + device.address)
                }
                sharedViewModel.listeAppareils.value = listeAppareils.toList()
            } else {
                val cached = sharedViewModel.listeAppareils.value
                if (!cached.isNullOrEmpty()) {
                    listeAppareils.addAll(cached)
                    Toast.makeText(activity, "BT éteint – dernière liste connue", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Activez le BT pour charger les appareils", Toast.LENGTH_SHORT).show()
                }
            }

            listView.adapter = activity?.let {
                object : ArrayAdapter<String>(it, android.R.layout.simple_list_item_1, listeAppareils) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as TextView).setTextColor(android.graphics.Color.BLACK)
                        return view
                    }
                }
            }
        }

        // ── Clic sur un appareil → connexion BT ─────────────────────────────
        listView.setOnItemClickListener { _, _, pos, _ ->
            val selectedItem = listeAppareils[pos]
            val address: String = selectedItem.split('\n')[1]
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)!!
            Log.i("BT", "Device choisi - ${device.name} - $address")

            listeAppareils.clear()
            listeAppareils.add(selectedItem)
            listView.adapter = activity?.let {
                object : ArrayAdapter<String>(it, android.R.layout.simple_list_item_1, listeAppareils) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as TextView).setTextColor(android.graphics.Color.BLACK)
                        return view
                    }
                }
            }

            btnStart.visibility = View.GONE
            btnInit.visibility = View.VISIBLE

            ConnectThread(device, bluetoothAdapter, handler) { ct ->
                connectedThread = ct
            }.start()
        }

        // ── Effacer liste ────────────────────────────────────────────────────
        btnEffacer.setOnClickListener {
            listeAppareils.clear()
            listView.adapter = null
        }

        // ── Bouton Initialiser ───────────────────────────────────────────────
        btnInit.setOnClickListener {
            connectedThread?.envoyerCommande("INIT")
                ?: Toast.makeText(activity, "Pas encore connecté à l'ESP32", Toast.LENGTH_SHORT).show()
        }

        // ── Bouton C'est parti ───────────────────────────────────────────────
        btnStart.setOnClickListener {
            connectedThread?.envoyerCommande("START")
                ?: Toast.makeText(activity, "Pas encore connecté à l'ESP32", Toast.LENGTH_SHORT).show()
        }

        // ── Navigation entre fragments ───────────────────────────────────────
        vue.findViewById<Button>(R.id.btnPage1).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Fragment1())
                .commit()
        }

        vue.findViewById<Button>(R.id.btnPage2).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Fragment2())
                .commit()
        }

        vue.findViewById<Button>(R.id.btnPage3).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Fragment3())
                .commit()
        }

        return vue
    }
}