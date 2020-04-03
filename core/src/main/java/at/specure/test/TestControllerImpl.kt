package at.specure.test

import android.content.Context
import android.net.ConnectivityManager
import at.rtr.rmbt.client.QualityOfServiceTest
import at.rtr.rmbt.client.RMBTClient
import at.rtr.rmbt.client.RMBTClientCallback
import at.rtr.rmbt.client.TracerouteAndroidImpl
import at.rtr.rmbt.client.TrafficServiceImpl
import at.rtr.rmbt.client.WebsiteTestServiceImpl
import at.rtr.rmbt.client.helper.IntermediateResult
import at.rtr.rmbt.client.helper.TestStatus
import at.rtr.rmbt.client.v2.task.result.QoSTestResultEnum
import at.rtr.rmbt.client.v2.task.service.TestSettings
import at.rtr.rmbt.util.model.shared.LoopModeSettings
import at.rtr.rmbt.util.model.shared.exception.ErrorStatus
import at.specure.config.Config
import at.specure.data.ClientUUID
import at.specure.data.MeasurementServers
import at.specure.info.Network5GSimulator
import at.specure.measurement.MeasurementState
import com.google.gson.Gson
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.json.JSONObject
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.floor

private const val KEY_TEST_COUNTER = "testCounter"
private const val KEY_PREVIOUS_TEST_STATUS = "previousTestStatus"
private const val KEY_LOOP_MODE_SETTINGS = "loopmode_info"
private const val KEY_SERVER_SELECTION_ENABLED = "user_server_selection"
private const val KEY_SERVER_PREFERRED = "prefer_server"
private const val KEY_DEVELOPER_MODE_ENABLED = "developer_mode"

private const val TEST_MAX_TIME = 3000
private const val MAX_VALUE_UNFINISHED_TEST = 0.9f

class TestControllerImpl(
    private val context: Context,
    private val config: Config,
    private val clientUUID: ClientUUID,
    private val connectivityManager: ConnectivityManager,
    private val measurementServer: MeasurementServers
) : TestController {

    private var job: Job? = null
    private var clientJob: Job? = null
    private val result: IntermediateResult by lazy { IntermediateResult() }
    private var _testUUID: String? = null
    private var _listener: TestProgressListener? = null
    private var _testStartTimeNanos = 0L

    override val testUUID: String?
        get() = _testUUID

    override val isRunning: Boolean
        get() = job != null

    override val testStartTimeNanos: Long
        get() = _testStartTimeNanos

    private var previousDownloadProgress = -1
    private var previousUploadProgress = -1

    private var client: RMBTClient? = null
    private var qosTest: QualityOfServiceTest? = null

    private var finalDownloadValuePosted = false
    private var finalUploadValuePosted = false

    override fun reset() {
        client?.shutdown()
        qosTest?.interrupt()

        if (clientJob == null) {
            Timber.w("client job is already stopped")
        } else {
            clientJob?.cancel()
        }

        if (job == null) {
            Timber.w("Runner is already stopped")
        } else {
            job?.cancel()
        }

        job = null
        clientJob = null
        _listener = null
        _testUUID = null
        finalDownloadValuePosted = false
        finalUploadValuePosted = false
        previousDownloadProgress = -1
        previousUploadProgress = -1
        _listener = null
    }

    override fun start(
        deviceInfo: DeviceInfo,
        loopModeUUID: String?,
        loopTestCount: Int,
        listener: TestProgressListener,
        clientCallback: RMBTClientCallback
    ) {
        if (job != null) {
            Timber.w("Runner is already started")
            return
        }

        _listener = listener

        job = GlobalScope.async {

            previousDownloadProgress = -1
            previousUploadProgress = -1
            finalDownloadValuePosted = false
            finalUploadValuePosted = false

            setState(MeasurementState.IDLE, 0)

            var geoInfo: ArrayList<String>? = null
            deviceInfo.location?.let {
                geoInfo = arrayListOf(
                    it.time.toString(),
                    it.lat.toString(),
                    it.long.toString(),
                    it.accuracy.toString(),
                    it.altitude.toString(),
                    it.bearing.toString(),
                    it.speed.toString(),
                    it.provider
                )
            }

            val loopSettings: LoopModeSettings? = if (config.loopModeEnabled) {
                LoopModeSettings(
                    config.loopModeWaitingTimeMin,
                    config.loopModeDistanceMeters,
                    config.loopModeNumberOfTests,
                    loopTestCount,
                    loopModeUUID
                )
            } else null

            val gson = Gson()
            val errorSet = mutableSetOf<ErrorStatus>()
            val additionalValues = JSONObject(gson.toJson(deviceInfo))
                .put(KEY_TEST_COUNTER, config.testCounter)
                .put(KEY_PREVIOUS_TEST_STATUS, config.previousTestStatus)

            loopSettings?.let {
                additionalValues.put(KEY_LOOP_MODE_SETTINGS, gson.toJson(it))
            }

            if (config.expertModeEnabled) {
                additionalValues.put(KEY_SERVER_SELECTION_ENABLED, true)
                measurementServer.selectedMeasurementServer?.let {
                    additionalValues.put(KEY_SERVER_PREFERRED, it.uuid)
                }
            }

            if (config.developerModeIsEnabled) {
                additionalValues.put(KEY_DEVELOPER_MODE_ENABLED, true)
            }

            client = RMBTClient.getInstance(
                config.controlServerHost,
                null,
                config.controlServerPort,
                config.controlServerUseSSL,
                geoInfo,
                clientUUID.value,
                deviceInfo.clientType,
                deviceInfo.clientName,
                deviceInfo.softwareVersionName,
                null,
                additionalValues,
                errorSet
            )

            val client = client

            if (client == null || errorSet.isNotEmpty()) {
                Timber.w("Client has errors")
                _listener?.onError()
                job?.cancel()
                job = null
                return@async
            }

            client.commonCallback = clientCallback
            client.trafficService = TrafficServiceImpl()

            val connection = client.controlConnection
            Timber.i("Client UUID: ${connection?.clientUUID}")
            Timber.i("Test UUID: ${connection.testUuid}")
            Timber.i("Server Name: ${connection?.serverName}")
            Timber.i("Loop Id: ${connection?.loopUuid}")
            Timber.i("Start Time Nanos: ${connection?.startTimeNs}")

            _testStartTimeNanos = connection?.startTimeNs ?: 0
            _testUUID = connection.testUuid

            _listener?.onClientReady(_testUUID!!, connection.loopUuid, _testStartTimeNanos)

            val skipQoSTests = config.skipQoSTests

            clientJob = GlobalScope.async {
                @Suppress("BlockingMethodInNonBlockingContext")
                val result = client.runTest()

                if (!finalUploadValuePosted) {
                    val speed = floor(client.totalTestResult.speed_upload + 0.5).toLong() * 1000
                    _listener?.onUploadSpeedChanged(-1, speed)
                    finalUploadValuePosted = true
                }

                clientCallback.onTestCompleted(result, !skipQoSTests)
                if (!skipQoSTests) { // needs to prevent calling onTestCompleted and finishing before unimplemented QoS phase
                    val qosTestSettings = TestSettings()
                    qosTestSettings.cacheFolder = context.cacheDir
                    qosTestSettings.websiteTestService = WebsiteTestServiceImpl(context)
                    qosTestSettings.tracerouteServiceClazz = TracerouteAndroidImpl::class.java
                    qosTestSettings.trafficService = TrafficServiceImpl()
                    qosTestSettings.startTimeNs = _testStartTimeNanos
                    qosTestSettings.isUseSsl = config.qosSSL

                    // get default dns servers
                    qosTestSettings.defaultDnsResolvers = getDnsServers()

                    qosTest = QualityOfServiceTest(client, qosTestSettings)
                    client.status = TestStatus.QOS_TEST_RUNNING
                    val qosResult = qosTest?.call()
                    Timber.d("qos finished")
                    client.status = TestStatus.QOS_END
                    clientCallback.onQoSTestCompleted(qosResult)
                }
            }

            var currentStatus = TestStatus.WAIT

            while (!currentStatus.isFinalState(skipQoSTests)) {
                currentStatus = client.status
                Timber.v(currentStatus.name)

                clientCallback.onTestStatusUpdate(currentStatus)

                when (currentStatus) {
                    TestStatus.WAIT -> handleWait()
                    TestStatus.INIT -> handleInit(client)
                    TestStatus.PING -> handlePing(client)
                    TestStatus.DOWN -> handleDown(client)
                    TestStatus.INIT_UP -> handleInitUp()
                    TestStatus.UP -> handleUp(client)
                    TestStatus.SPEEDTEST_END -> handleSpeedTestEnd(skipQoSTests)
                    TestStatus.QOS_TEST_RUNNING -> handleQoSRunning(qosTest)
                    TestStatus.QOS_END -> handleQoSEnd()
                    TestStatus.ERROR -> handleError(client)
                    TestStatus.END -> handleEnd()
                    TestStatus.ABORTED -> handleAbort(client)
                }

                if (currentStatus.isFinalState(skipQoSTests)) {
                    // todo check correctness of counter's values after implementing QoS logic
                    config.testCounter++
                    config.previousTestStatus = if (currentStatus == TestStatus.ERROR) {
                        var errorStatus = "ERROR"
                        client.statusBeforeError?.let {
                            errorStatus = "${errorStatus}_$it"
                        }
                        errorStatus
                    } else TestStatus.END.name

                    client.commonCallback = null
                    client.shutdown()
                    qosTest?.interrupt()

                    this@TestControllerImpl.client = null
                    qosTest = null
                    if (currentStatus != TestStatus.ERROR) {
                        _listener?.onFinish()
                    }
                    stop()
                    _testUUID = null
                } else {
                    delay(100)
                }
            }
        }
    }

    private fun handleWait() {
        setState(MeasurementState.IDLE, 0)
    }

    private fun handleInit(client: RMBTClient) {
        client.getIntermediateResult(result)
        setState(MeasurementState.INIT, (result.progress * 100).toInt())
    }

    private fun handlePing(client: RMBTClient) {
        client.getIntermediateResult(result)
        setState(MeasurementState.PING, (result.progress * 100).toInt())
    }

    private fun handleDown(client: RMBTClient) {
        client.getIntermediateResult(result)
        val progress = (result.progress * 100).toInt()
        if (progress != previousDownloadProgress) {
            setState(MeasurementState.DOWNLOAD, progress)
            if (result.pingNano > 0) {
                _listener?.onPingChanged(result.pingNano)
            }
            val value = Network5GSimulator.downBitPerSec(result.downBitPerSec)
            _listener?.onDownloadSpeedChanged(progress, value)
            previousDownloadProgress = progress
        }
    }

    private fun handleInitUp() {
        setState(MeasurementState.UPLOAD, 0)
    }

    private fun handleUp(client: RMBTClient) {
        client.getIntermediateResult(result)
        val progress = (result.progress * 100).toInt()
        if (progress != previousUploadProgress) {
            setState(MeasurementState.UPLOAD, (result.progress * 100).toInt())
            if (result.pingNano > 0) {
                _listener?.onPingChanged(result.pingNano)
            }
            val value = Network5GSimulator.upBitPerSec(result.upBitPerSec)
            _listener?.onUploadSpeedChanged(progress, value)
            previousUploadProgress = progress
        }

        if (!finalDownloadValuePosted) {
            val value = Network5GSimulator.downBitPerSec(result.downBitPerSec)
            _listener?.onDownloadSpeedChanged(-1, value)
            finalDownloadValuePosted = true
        }
    }

    private fun handleSpeedTestEnd(skipQoSTest: Boolean) {
        if (skipQoSTest) {
            setState(MeasurementState.FINISH, 0)
        }
    }

    private fun handleQoSRunning(qosTest: QualityOfServiceTest?) {
        qosTest ?: return
        Timber.i("${TestStatus.QOS_TEST_RUNNING} progress: ${qosTest.progress}/${qosTest.testSize}")
        val progress = ((qosTest.progress.toDouble() / qosTest.testSize) * 100).toInt()
        setState(MeasurementState.QOS, progress)

        val counterMap = qosTest.testGroupCounterMap
        val testMap = qosTest.testMap

        val progressMap = mutableMapOf<QoSTestResultEnum, Int>()

        counterMap.forEach { entry ->
            val key = entry.key!!
            val counter = entry.value!!

            var testGroupProgress = 0f
            val currentTimestamp = System.nanoTime()

            val taskList = testMap[key]
            if (taskList != null && counter.value < counter.target) {
                taskList.forEach { task ->
                    if (task.hasStarted()) {
                        val runningMs = TimeUnit.NANOSECONDS.toMillis(task.getRelativeDurationNs(currentTimestamp))
                        if (runningMs >= TEST_MAX_TIME * MAX_VALUE_UNFINISHED_TEST && !task.hasFinished()) {
                            testGroupProgress += (1f / counter.target) * MAX_VALUE_UNFINISHED_TEST
                        } else if (!task.hasFinished()) {
                            testGroupProgress += (1f / counter.target) * (runningMs / TEST_MAX_TIME)
                        }
                    }
                }

                testGroupProgress += counter.value.toFloat() / counter.target
                testGroupProgress *= 100f
            } else if (counter.value == counter.target) {
                testGroupProgress = 100f
            } else {
                Timber.w("Task $key not found!")
            }

            progressMap[key] = testGroupProgress.toInt()
            Timber.i("$key : $testGroupProgress")
        }

        _listener?.onQoSTestProgressUpdate(qosTest.progress, qosTest.testSize, progressMap)
    }

    private fun handleQoSEnd() {
        setState(MeasurementState.FINISH, 0)
        _testUUID = null
    }

    private fun handleError(client: RMBTClient) {
        _listener?.onError()
        _testUUID = null
    }

    private fun handleAbort(client: RMBTClient) {
        Timber.e("${TestStatus.ABORTED} handling not implemented")
        _testUUID = null
    }

    private fun handleEnd() {
        setState(MeasurementState.FINISH, 0)
        _testUUID = null
    }

    private fun setState(state: MeasurementState, progress: Int) {
        _listener?.onProgressChanged(state, progress)
    }

    override fun stop() {
        client?.shutdown()
        qosTest?.interrupt()

        if (clientJob == null) {
            Timber.w("client job is already stopped")
        } else {
            clientJob?.cancel()
        }

        if (job == null) {
            Timber.w("Runner is already stopped")
        } else {
            job?.cancel()
        }

        job = null
        clientJob = null
        _listener = null
    }

    private fun TestStatus.isFinalState(skipQoSTest: Boolean) = this == TestStatus.ABORTED ||
            this == TestStatus.END ||
            this == TestStatus.ERROR ||
            (this == TestStatus.SPEEDTEST_END && skipQoSTest) ||
            this == TestStatus.QOS_END

    private fun getDnsServers(): List<InetAddress>? {
        val servers = mutableListOf<InetAddress>()
        val networks = arrayOf(connectivityManager.activeNetwork)
        for (i in networks.indices) {
            val linkProperties = connectivityManager.getLinkProperties(networks[i])
            if (linkProperties != null) {
                servers.addAll(linkProperties.dnsServers)
            }
        }
        servers.forEach {
            Timber.d("DNS Server: ${it.hostName} (${it.hostAddress})")
        }
        return servers
    }
}