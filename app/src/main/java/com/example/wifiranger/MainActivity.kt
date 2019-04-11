package com.example.wifiranger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
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

class MainActivity : AppCompatActivity() {

    val self = this

    //Range Loop
    var isRanging: Boolean = false

    //Discovery Sessions
    var pubDiscoverySession: PublishDiscoverySession ?= null
    var subDiscoverySession: SubscribeDiscoverySession ?= null

    var pubsub: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPublish.setOnClickListener(){
            pubsub = 0
            wifiAwareAttach(pubsub)
        }

        btnSubscribe.setOnClickListener(){
            pubsub = 1
            wifiAwareAttach(pubsub)
        }

        btnStopRange.setOnClickListener(){
            isRanging = false
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
            //.setTtlSec(0)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()

        awareSession.publish(config,object:DiscoverySessionCallback(){
            override fun onPublishStarted(session: PublishDiscoverySession) {
                super.onPublishStarted(session)
                pubDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle?, message: ByteArray?) {
                super.onMessageReceived(peerHandle, message)
                val peertest = peerHandle
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
            //.setTtlSec(60)
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

                rangeStart(peerHandle)
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


    //80211mc Check
    private fun scan80211mc(){

        if(!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)){
            return
        }
        val wifiManager = this.getSystemService((Context.WIFI_SERVICE)) as WifiManager

        val wifiScanReceiver = object:BroadcastReceiver(){

            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if(success) {
                    //scanSuccess()
                } else {
                    //scanFailure()
                }
            }
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        this.registerReceiver(wifiScanReceiver, intentFilter)

        val success2 = wifiManager.startScan()

        val scanResult: MutableList<ScanResult> = wifiManager.scanResults
        for (i in scanResult.indices){
            if (!wifiManager.scanResults[i].is80211mcResponder){
                //scanResult.removeAt(i)
            } else{
                val testScan = scanResult[i].SSID
            }
      }
        val testFail = scanResult
    }

    //WIFI RTT Ranging
    private fun rangeStart(peerHandle: PeerHandle){
        val peerTest = peerHandle
        if(packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)){
            val wifiRttManager = this.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as WifiRttManager
            Log.d("RTTService", wifiRttManager.isAvailable.toString())
            val filter = IntentFilter(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED)
            val myReceiver = object: BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
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
                    }
                }
            this.registerReceiver(myReceiver, filter)

            if (wifiRttManager.isAvailable) {
                Log.d("RTTService", wifiRttManager.isAvailable.toString())
                val request: RangingRequest = RangingRequest.Builder().run {
                    addWifiAwarePeer(peerHandle)
                    build()
                }
                if(ContextCompat.checkSelfPermission(self, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    wifiRttManager.startRanging(request, mainExecutor, object : RangingResultCallback() {
                        override fun onRangingResults(results: List<RangingResult>) {
                            for (result in results){
                                val blah = result.peerHandle

                                //Update Distance, don't crash
                                if(result.status == 0) {
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
    }
