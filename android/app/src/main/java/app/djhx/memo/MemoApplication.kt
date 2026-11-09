package app.djhx.memo

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** UI 与 WorkManager 复用单例 MemoApp，避免同一 outbox 并发发送。 */
class MemoApplication : Application() {
    val gateway: CoreGateway by lazy { CoreGateway(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            appScope.launch { runCatching { gateway.call("sync") } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("memo-sync", ExistingPeriodicWorkPolicy.KEEP, work)
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(networkCallback)
    }
}

/** 后台只请求 core 的本轮同步，不复制业务规则，也不承诺固定运行时刻。 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val gateway = (applicationContext as MemoApplication).gateway
        val result = gateway.obj("sync", "wait" to true)
        if (shouldRetrySync(
                result.optString("state"),
                result.optJSONObject("error")?.optBoolean("retryable") == true
            )
        ) {
            Result.retry()
        } else {
            Result.success()
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: CoreFailure) {
        if (error.retryable) Result.retry() else Result.success()
    } catch (_: Exception) {
        Result.failure()
    }
}

/** 只有核心明确标为临时的网络/服务错误才交由 WorkManager 重试。 */
internal fun shouldRetrySync(
    state: String,
    retryable: Boolean
): Boolean = state in setOf("offline", "error") && retryable
