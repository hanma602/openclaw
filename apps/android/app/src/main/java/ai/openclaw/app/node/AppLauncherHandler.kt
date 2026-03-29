package ai.openclaw.app.node

import android.content.Intent
import android.net.Uri
import android.util.Log
import ai.openclaw.app.gateway.GatewaySession
import org.json.JSONObject

private const val TAG = "AppLauncherHandler"

class AppLauncherHandler(
  private val context: android.content.Context,
) {
  
  /**
   * 启动 Bilibili 播放指定视频
   * @param paramsJson {"bvId": "BV1HaX5BGE3a"} 或 {"url": "https://b23.tv/xxx"}
   */
  fun handleLaunchBilibili(paramsJson: String?): GatewaySession.InvokeResult {
    try {
      val bvId = extractBvId(paramsJson)
        ?: return GatewaySession.InvokeResult.error(
          code = "INVALID_REQUEST",
          message = "INVALID_REQUEST: bvId or url required",
        )
      
      val intent = Intent(Intent.ACTION_VIEW).apply {
        // 尝试使用 Bilibili 自定义协议打开
        data = Uri.parse("bilibili://video/$bvId")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
      
      // 检查是否安装了 Bilibili
      val resolveInfo = context.packageManager.resolveActivity(intent, 0)
      if (resolveInfo != null) {
        context.startActivity(intent)
        Log.i(TAG, "Launched Bilibili with BV id: $bvId")
        return GatewaySession.InvokeResult.ok("""{"launched": true, "bvId": "$bvId", "app": "tv.danmaku.bili"}""")
      } else {
        // Bilibili 未安装，使用浏览器打开
        val browserIntent = Intent(Intent.ACTION_VIEW).apply {
          data = Uri.parse("https://m.bilibili.com/video/$bvId")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
        Log.i(TAG, "Bilibili not installed, opened in browser: $bvId")
        return GatewaySession.InvokeResult.ok("""{"launched": true, "bvId": "$bvId", "app": "browser", "fallback": true}""")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch Bilibili", e)
      return GatewaySession.InvokeResult.error(
        code = "APP_LAUNCH_FAILED",
        message = "APP_LAUNCH_FAILED: ${e.message}",
      )
    }
  }
  
  /**
   * 启动任意应用（通过包名）
   * @param paramsJson {"packageName": "tv.danmaku.bili", "action": "android.intent.action.VIEW", "data": "bilibili://..."}
   */
  fun handleLaunchApp(paramsJson: String?): GatewaySession.InvokeResult {
    try {
      val params = paramsJson?.let { JSONObject(it) }
        ?: return GatewaySession.InvokeResult.error(
          code = "INVALID_REQUEST",
          message = "INVALID_REQUEST: params required",
        )
      
      val packageName = params.optString("packageName")
        ?: return GatewaySession.InvokeResult.error(
          code = "INVALID_REQUEST",
          message = "INVALID_REQUEST: packageName required",
        )
      
      val intent = Intent().apply {
        setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        params.optString("action")?.let { action = it }
        params.optString("data")?.let { data = Uri.parse(it) }
        params.optString("className")?.let { setClassName(packageName, it) }
      }
      
      val resolveInfo = context.packageManager.resolveActivity(intent, 0)
      if (resolveInfo != null) {
        context.startActivity(intent)
        Log.i(TAG, "Launched app: $packageName")
        return GatewaySession.InvokeResult.ok("""{"launched": true, "packageName": "$packageName"}""")
      } else {
        return GatewaySession.InvokeResult.error(
          code = "APP_NOT_FOUND",
          message = "APP_NOT_FOUND: $packageName not installed",
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch app", e)
      return GatewaySession.InvokeResult.error(
        code = "APP_LAUNCH_FAILED",
        message = "APP_LAUNCH_FAILED: ${e.message}",
      )
    }
  }
  
  /**
   * 打开 B 站收藏夹
   * @param paramsJson {"folderId": "123"} (可选，默认打开全部收藏)
   */
  fun handleOpenBilibiliFavorites(paramsJson: String?): GatewaySession.InvokeResult {
    try {
      val intent = Intent(Intent.ACTION_VIEW).apply {
        // B 站收藏夹协议
        data = Uri.parse("bilibili://main/favorites")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      
      val resolveInfo = context.packageManager.resolveActivity(intent, 0)
      if (resolveInfo != null) {
        context.startActivity(intent)
        Log.i(TAG, "Opened Bilibili favorites")
        return GatewaySession.InvokeResult.ok("""{"launched": true, "target": "favorites", "app": "tv.danmaku.bili"}""")
      } else {
        // 使用浏览器打开 B 站个人中心
        val browserIntent = Intent(Intent.ACTION_VIEW).apply {
          data = Uri.parse("https://space.bilibili.com/")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
        Log.i(TAG, "Bilibili not installed, opened space in browser")
        return GatewaySession.InvokeResult.ok("""{"launched": true, "target": "favorites", "app": "browser", "fallback": true}""")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open Bilibili favorites", e)
      return GatewaySession.InvokeResult.error(
        code = "APP_LAUNCH_FAILED",
        message = "APP_LAUNCH_FAILED: ${e.message}",
      )
    }
  }
  
  private fun extractBvId(paramsJson: String?): String? {
    if (paramsJson == null) return null
    
    return try {
      val params = JSONObject(paramsJson)
      
      // 直接提供 BV 号
      params.optString("bvId").takeIf { it.isNotBlank() }
        ?: params.optString("BV").takeIf { it.isNotBlank() }
        // 从 URL 中提取 BV 号
        ?: params.optString("url")?.let { url ->
          extractBvIdFromUrl(url)
        }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse params", e)
      null
    }
  }
  
  private fun extractBvIdFromUrl(url: String): String? {
    // 匹配 BV 号：BV 后跟 10 位字母数字
    val bvPattern = Regex("BV[0-9a-zA-Z]{10}")
    return bvPattern.find(url)?.value
  }
}
