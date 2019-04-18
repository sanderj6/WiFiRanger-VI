package com.example.wifiranger

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.aware.*
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    val self = this

    //Bluetooth Declarations
    private var outStream: OutputStream? = null
    private var inStream: InputStream? = null
    val blueAdapter = BluetoothAdapter.getDefaultAdapter()
    val NAME = "ServerPOS"
    val UUID = java.util.UUID.fromString("08794f7e-8d41-47f2-ad9d-be7e696884ca")

    //Range Loop
    var isRanging: Boolean = false

    //Discovery Sessions
    var pubDiscoverySession: PublishDiscoverySession ?= null
    var subDiscoverySession: SubscribeDiscoverySession ?= null

    var pubsub: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPublish.setOnClickListener{
            pubsub = 0
            wifiAwareAttach(pubsub)
        }

        btnSubscribe.setOnClickListener{
            pubsub = 1
            wifiAwareAttach(pubsub)
        }

        btnStopRange.setOnClickListener{
            isRanging = false
        }

        btnServer.setOnClickListener{
            serverSetup()
        }
    }

    //WiFiAwareAttach
    private fun wifiAwareAttach(pubsub: Int){

        //Aware and RTT checks
        if(!this.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)){
            return
        }
        if(!this.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)){
            return
        }

        val wifiAwareManager = this.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager
        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)

        //Receiver - Aware
        val myReceiver = object:BroadcastReceiver(){
            override fun onReceive(context:Context, intent:Intent){
                if (wifiAwareManager.isAvailable){
                    Log.d("yo", "aware is working")
                }
            }
        }
        this.registerReceiver(myReceiver,filter)

        //ATTACH AWARE
        wifiAwareManager.attach(object:AttachCallback(){
            override fun onAttached(session:WifiAwareSession){
                val awareSession: WifiAwareSession = session
                //pubsub Check
                if(pubsub ==0){
                    attachPublisher(awareSession)
                } else{
                    attachSubscriber(awareSession)
                }
            }
            override fun onAttachFailed(){
                super.onAttachFailed()
            }
        }, null)
    }

    //Publisher
    private fun attachPublisher(awareSession: WifiAwareSession){

        //CONFIG
        val config: PublishConfig = PublishConfig.Builder()
            .setServiceName("JoshPub")
            .setRangingEnabled(true)
            .setTerminateNotificationEnabled(true)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()

        awareSession.publish(config,object:DiscoverySessionCallback(){
            override fun onPublishStarted(session: PublishDiscoverySession) {
                super.onPublishStarted(session)
                pubDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle?, message: ByteArray?) {
                super.onMessageReceived(peerHandle, message)
            }
        }, null)
    }

    //Subscriber
    private fun attachSubscriber(awareSession: WifiAwareSession){

        //CONFIG
        val config: SubscribeConfig = SubscribeConfig.Builder()
            .setServiceName("JoshPub")
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTerminateNotificationEnabled(true)
            .setMinDistanceMm(0)
            .build()

        awareSession.subscribe(config,object:DiscoverySessionCallback(){

            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                super.onSubscribeStarted(session)
                subDiscoverySession = session
            }

            override fun onServiceDiscoveredWithinRange(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?,
                distanceMm: Int
            ) {
                super.onServiceDiscoveredWithinRange(peerHandle, serviceSpecificInfo, matchFilter, distanceMm)
                isRanging = true
                rangeStart(peerHandle) //Begin Ranging
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray?) {
                super.onMessageReceived(peerHandle, message)

                //Network Request
                val networkConnectManager = self.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                var myNetworkRequest: NetworkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(subDiscoverySession?.createNetworkSpecifierOpen(peerHandle))
                    .build()

                val callback = object:ConnectivityManager.NetworkCallback(){

                    override fun onAvailable(network: Network?) {
                        super.onAvailable(network)
                        rangeStart(peerHandle)
                    }

                    override fun onLinkPropertiesChanged(network: Network?, linkProperties: LinkProperties?) {
                        super.onLinkPropertiesChanged(network, linkProperties)
                    }

                    override fun onLost(network: Network?) {
                        super.onLost(network)
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                    }
                }

                networkConnectManager.activeNetwork
                networkConnectManager.requestNetwork(myNetworkRequest,callback)

            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>
            ) {
                super.onServiceDiscovered(peerHandle, serviceSpecificInfo, matchFilter)
                txtNetwork2.text = "Service Discovered"

                //Send SUBSCRIBER message to PUBLISHER
                val msgId = 69
                val matchByte = matchFilter
                val msgByte = byteArrayOf(-0x80, -0x79, 0x00, 0x79)
                subDiscoverySession?.sendMessage(peerHandle,msgId,msgByte) //is the subdiscoverysession = session or null?

            }
        }, null)
    }

    //WIFI RTT Ranging
    private fun rangeStart(peerHandle: PeerHandle){

        //RTT Check
        if(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)){
            val wifiRttManager = this.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as WifiRttManager
            Log.d("RTTService", wifiRttManager.isAvailable.toString())
            val filter = IntentFilter(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED)
            val myReceiver = object: BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { //Is onReceive necessary???
//                    if (wifiRttManager.isAvailable) {
//                        Log.d("RTTService", wifiRttManager.isAvailable.toString())
//                        val request: RangingRequest = RangingRequest.Builder().run {
//                            addWifiAwarePeer(peerHandle)
//                            build()
//                        }
//                        if(ContextCompat.checkSelfPermission(self, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//                            wifiRttManager.startRanging(request, mainExecutor, object : RangingResultCallback() {
//                                override fun onRangingResults(results: List<RangingResult>) {
//                                    for (result in results){
//                                        Log.d("RangingResult", result.status.toString())
//                                        Log.d("RangingResult", result.distanceMm.toString())
//                                    }
//                                }
//
//                                override fun onRangingFailure(code: Int) {
//                                    Log.e("Error!", code.toString())
//                                }
//                            })
//                        }
//
//                    } else {
//
//                    }
                    } //Is this necessary??
                }
            this.registerReceiver(myReceiver, filter)

            if (wifiRttManager.isAvailable) {
                Log.d("RTTService", wifiRttManager.isAvailable.toString())
                val request: RangingRequest = RangingRequest.Builder().run {
                    addWifiAwarePeer(peerHandle) //add WiFi Aware peer
                    build()
                }
                if(ContextCompat.checkSelfPermission(self, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    wifiRttManager.startRanging(request, mainExecutor, object : RangingResultCallback() {
                        override fun onRangingResults(results: List<RangingResult>) {
                            for (result in results){

                                //Update Distance, don't crash
                                if(result.status == 0) {
                                    //**WRITE BLUETOOTH HERE
                                    txtDistance.text = result.distanceMm.toString()
                                } else {
                                    val breakme = result
                                }

                                //Restart Ranging if StopRanging button not pressed
                                if (isRanging){
                                    rangeStart(peerHandle)
                                }
                            }
                        }

                        override fun onRangingFailure(code: Int) {
                            Log.e("Error!", code.toString())
                        }
                    })
                }
            } else {
            }
            }
        }

    //Bluetooth - Client Connect
    @Throws(IOException::class)
    private fun clientConnect() {
        if (blueAdapter != null) {
            if (blueAdapter.isEnabled) {
                val bondedDevices = blueAdapter.bondedDevices

                if (bondedDevices.size > 0) {
                    val MY_UUID = UUID
                    val devices = bondedDevices.toTypedArray() as Array<Any>
                    val device = devices[0] as BluetoothDevice
                    val uuids = device.uuids
                    var socket = device.createRfcommSocketToServiceRecord(MY_UUID)

                    try {
                        socket.connect()
                        Log.e("", "Connected")
                    } catch (e: IOException) {
                        Log.e("", e.message)
                        try {
                            Log.e("", "trying fallback...")

                            socket = device.javaClass.getMethod(
                                "createRfcommSocket",
                                *arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)
                            ).invoke(device, 2) as BluetoothSocket
                            socket.connect()
                            val testsocket = socket
                            Log.e("", "Connected")
                        } catch (e2: Exception) {
                            Log.e("", "Couldn't establish Bluetooth connection!")
                        }

                    }
                    readBuffer(socket) //Read incoming socket data
                }
            } else {
                Log.e("error", "Bluetooth is disabled.")
            }
        }
    }

    //Bluetooth - Read incoming buffer stream
    private fun readBuffer(s: BluetoothSocket){
        val mmInStream: InputStream = s.inputStream
        val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream
        var numBytes: Int // bytes returned from read()

        // Listen for Input Stream
        while (true) {
            numBytes = try {
                mmInStream.read(mmBuffer)
            } catch (e: IOException) {
                //Log.d(TAG, "Input stream was disconnected", e)
                break
            }

            // Parse buffer and update UI
            val readMsg = mmBuffer.toString()

        }
    }

    //Bluetooth - Setup Server
    private fun serverSetup() {

        var socket: BluetoothSocket?= null
        val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) { //Bluetooth Server Socket Setup
            blueAdapter?.listenUsingInsecureRfcommWithServiceRecord(NAME, UUID)
        }

        // Listen for Socket
        var shouldLoop = true
        while (shouldLoop) {
            socket = try {
                mmServerSocket?.accept() //Accept Socket, create connection
            } catch (e: IOException) {
                //Log.e(TAG, "Socket's accept() method failed", e)
                shouldLoop = false
                null
            }
            socket?.also {
                //manageMyConnectedSocket(it)
                mmServerSocket?.close()
                shouldLoop = false
            }
        }

        //Declare Output Stream
        outStream = socket?.outputStream

        val teststring = "butt" //**REPLACE WITH DISTANCEmm + #
        while(true) {
            outStream!!.write(teststring.toByteArray(charset("UTF-8")) + "#".toByteArray(charset("UTF-8")))
        }
    }
}
