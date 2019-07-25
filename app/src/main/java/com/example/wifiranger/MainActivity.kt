package com.example.wifiranger

import android.bluetooth.BluetoothAdapter
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
import android.os.Handler
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.WindowManager
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.doAsync
import java.io.IOException
import java.io.OutputStream
import java.util.*

// Constants
const val SERVICE_NAME: String = "ranger"
const val RANGE_RESULT_HISTORY_SIZE = 500
const val SMALL_MEAN_SIZE = 5
const val LARGE_MEAN_SIZE = 50

class MainActivity : AppCompatActivity(){

    val self = this


    //Bluetooth Declarations
    private var outStream: OutputStream? = null
    val blueAdapter = BluetoothAdapter.getDefaultAdapter()
    val NAME = "ServerPOS"
    val UUID = java.util.UUID.fromString("08794f7e-8d41-47f2-ad9d-be7e696884ca")
    var newDistance = 0
    var oldDistance = 0

    //Range Loop
    var isRanging: Boolean = false

    //Discovery Sessions
    var pubDiscoverySession: PublishDiscoverySession ?= null
    var subDiscoverySession: SubscribeDiscoverySession ?= null

    //WifiAware Session
    var awareSession: WifiAwareSession? = null
    var wifiAwarePeerHandle: PeerHandle ?= null

    //Bluetooth Session
    var socket: BluetoothSocket?= null

    var isPublisher: Boolean = true
    var audioCheck: Int = 0

    private var prevRangeVals:MutableList<Int> = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //callAsynchronousRanging()

        pubSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                //PUBLISH
                isPublisher = true
                subDiscoverySession?.close()
                wifiAwareAttach(isPublisher)
            } else{
                pubDiscoverySession?.close()
                pubStatus.text = "Disconnected"
            }
        }

        subSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                //SUBSCRIBE
                isPublisher = false
                pubDiscoverySession?.close()
                wifiAwareAttach(isPublisher)
            } else {
                subDiscoverySession?.close()
                subStatus.text = "Disconnected"
            }
        }

        audioSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                audioCheck = 1
            }

        }

        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                //Turn on Debugging messages
                subStatus.visibility = VISIBLE
                audibleStatus.visibility = VISIBLE
            } else {
                //Turn off Debugging messages
                subStatus.visibility = INVISIBLE
                audibleStatus.visibility = INVISIBLE
            }
        }

        btn_Exit.setOnClickListener(){
            subDiscoverySession?.close()
            awareSession?.close()
            outStream?.close()
            socket?.close()

            finish()
        }
    }

    override fun onDestroy() {
        subDiscoverySession?.close()
        awareSession?.close()
        outStream?.close()
        socket?.close()


        super.onDestroy()
    }

    //Async Audio Feedback
    fun callAsynchronousTask() {
        val handler = Handler()
        val timer = Timer()
        var audioMessage = ""
        val doAsynchronousTask = object : TimerTask() {
            override fun run() {
                handler.post(Runnable {
                    try {
                        doAsync{
                            audioMessage = newDistance.toString() + " meters"

                            if (newDistance != oldDistance) {
                                TTS(this@MainActivity, audioMessage, audioSwitch.isChecked)
                            }
                        }
                    } catch (e: Exception) {
                        // TODO Auto-generated catch block
                        val testbreak = "butt"
                    }
                })
            }
        }
        timer.schedule(doAsynchronousTask, 0, 3000) //execute in every 1000 ms
    }

    //Async Bluetooth Writing and Audio Feedback
//    fun callAsynchronousRanging() {
//        val handler = Handler()
//        val timer = Timer()
//        var audioMessage = ""
//
//        val doAsynchronousRangingTask = object : TimerTask() {
//            override fun run() {
//                handler.post(Runnable {
//                    try {
//                        doAsync{
//
//                            if (newDistance != oldDistance) { //check if distance has been updated
//                                txtDistance.text = newDistance.toString()
//                                audibleStatus.text = audioMessage
//
//                                outStream!!.write(newDistance)
//                                oldDistance = newDistance
//
//                                if (audioCheck == 1){
//                                    audioMessage = newDistance.toString() + " meters"
//                                    TTS(this@MainActivity, audioMessage, audioSwitch.isChecked)
//                                }
//                            }
//                        }
//                    } catch (e: Exception) {
//                        // TODO Auto-generated catch block
//                        val testbreak = "butt"
//                    }
//                })
//            }
//        }
//        timer.schedule(doAsynchronousRangingTask, 0, 500) //execute in every 500 ms
//    }

    //WiFiAwareAttach
    private fun wifiAwareAttach(isPublisher: Boolean){

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
                awareSession = session //make this class-level?

                //pubsub Check
                if(isPublisher){
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
    private fun attachPublisher(awareSession: WifiAwareSession?){

        //CONFIG
        val config: PublishConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setRangingEnabled(true)
            .setTerminateNotificationEnabled(true)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()

        try {
            awareSession?.publish(config,object:DiscoverySessionCallback(){
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    super.onPublishStarted(session)
                    pubDiscoverySession = session
                    pubStatus.text = "Connected"
                }

                override fun onMessageReceived(peerHandle: PeerHandle?, message: ByteArray?) {
                    super.onMessageReceived(peerHandle, message)
                }
            }, null)
        } catch (e: java.lang.Exception) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                this.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }

    }

    //Subscriber
    private fun attachSubscriber(awareSession: WifiAwareSession?){

        //CONFIG
        val config: SubscribeConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTerminateNotificationEnabled(true)
            .setMinDistanceMm(0)
            .build()

        try {
            awareSession?.subscribe(config, object : DiscoverySessionCallback() {

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
                    subStatus.text = "Connected"

                    bluetoothServerSetup(peerHandle) //Connect Bluetooth
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray?) {
                    super.onMessageReceived(peerHandle, message)

                    //Network Request
                    val networkConnectManager =
                        self.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    var myNetworkRequest: NetworkRequest = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                        .setNetworkSpecifier(subDiscoverySession?.createNetworkSpecifierOpen(peerHandle))
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {

                        override fun onAvailable(network: Network?) {
                            super.onAvailable(network)
                            rangeStart(peerHandle, outStream)
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
                    networkConnectManager.requestNetwork(myNetworkRequest, callback)

                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    super.onServiceDiscovered(peerHandle, serviceSpecificInfo, matchFilter)

                    //Send SUBSCRIBER message to PUBLISHER
                    val msgId = 69
                    val matchByte = matchFilter
                    val msgByte = byteArrayOf(-0x80, -0x79, 0x00, 0x79)
                    subDiscoverySession?.sendMessage(
                        peerHandle,
                        msgId,
                        msgByte
                    ) //is the subdiscoverysession = session or null?

                }
            }, null)
        }
        catch (e: Exception)
        {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                this.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
    }

    //Bluetooth - Setup Server
    private fun bluetoothServerSetup(peerHandle: PeerHandle) {

        val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) { //Bluetooth Server Socket Setup
            blueAdapter?.listenUsingInsecureRfcommWithServiceRecord(NAME, UUID)
        }

        // Listen for Socket
        var shouldLoop = false // Set this back to true, just want to skip the BT part right now
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
        rangeStart(peerHandle, outStream)

    }

    //WIFI RTT Ranging
    private fun rangeStart(peerHandle: PeerHandle, outputStream: OutputStream?){

        outStream = outputStream

        //RTT Check
        if(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)){
            val wifiRttManager = this.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as WifiRttManager
            Log.d("RTTService", wifiRttManager.isAvailable.toString())
            val filter = IntentFilter(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED)

//            val myReceiver = object: BroadcastReceiver() {
//                override fun onReceive(context: Context, intent: Intent) { //Is onReceive necessary???
//                    val test69 = context
//                    }
//                }
//            this.registerReceiver(myReceiver, filter)
//            this.unregisterReceiver(myReceiver)

            if (wifiRttManager.isAvailable) {
                Log.d("RTTService", wifiRttManager.isAvailable.toString())
                val request: RangingRequest = RangingRequest.Builder().run {
                    addWifiAwarePeer(peerHandle) //add WiFi Aware peer
                    build()
                }

                requestRange(wifiRttManager, request, peerHandle, outputStream)

            }
        }
    }

    private fun requestRange(wifiRttManager: WifiRttManager, request: RangingRequest, peerHandle: PeerHandle, outputStream: OutputStream?)
    {
        if(ContextCompat.checkSelfPermission(self, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            wifiRttManager.startRanging(request, mainExecutor, object : RangingResultCallback() {
                override fun onRangingResults(results: List<RangingResult>) {
                    for (result in results) {

                        //Update Distance, don't crash
                        if (result.status == RangingResult.STATUS_SUCCESS) {
                            //**WRITE BLUETOOTH HERE
                            newDistance = result.distanceMm / 2// / 2000

                            if (prevRangeVals.size >= RANGE_RESULT_HISTORY_SIZE) {
                                prevRangeVals.removeAt(0)
                            }
                            prevRangeVals.add(newDistance)


                            txtDistance.text = (newDistance.toFloat() / 1000.0).toString()
                            txtDistanceSmallMean.text = (PrevRangeValsAvgOfTop(SMALL_MEAN_SIZE).toFloat() / 1000.0).toString()
                            txtDistanceLargeMean.text = (PrevRangeValsAvgOfTop(LARGE_MEAN_SIZE).toFloat() / 1000.0).toString()
                            if (outStream != null) {
                                outStream!!.write(newDistance)
                            }
                            //outStream!!.flush()
                        } else {
                            val breakme = result
                        }

                        //Restart Ranging if StopRanging button not pressed
                        if (isRanging) {
                            requestRange(wifiRttManager, request, peerHandle, outputStream)
                        }
                    }
                }

                override fun onRangingFailure(code: Int) {
                    Log.e("Error!", code.toString())
                    requestRange(wifiRttManager, request, peerHandle, outputStream)
                }
            })
        }
    }

    // Returns the average of the values in PrevRangeResults
    // Only returns the average of the most recent values, up to a maximum of the provided count
    private fun PrevRangeValsAvgOfTop(count: Int) : Int
    {
        var numToAvg = this.prevRangeVals.size
        if (count < numToAvg)
        {
            numToAvg = count
        }

        var sum = 0
        var i = this.prevRangeVals.size - numToAvg
        while (i < this.prevRangeVals.size)
        {
            sum += this.prevRangeVals[i]
            i++
        }

        return (sum / numToAvg)
    }
}
