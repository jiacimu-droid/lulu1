package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluBackgroundPhoneSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression: a registered phone tool is not "missing" just because its
 * permissions/ADB transport are incomplete. In-chat models must see both
 * the real tool name and the separately verified authorization stages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BackgroundPhoneCapabilityContextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val characterId = "background-phone-context-test"

    @Test fun previouslyUnpairedAndNotAuthorizedStillShowsRegisteredTool() {
        val rolePrefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val adbPrefs = context.getSharedPreferences("lulu_local_wireless_debug", Context.MODE_PRIVATE)
        val oldRole = rolePrefs.getBoolean("$characterId:screen", false)
        val hadRole = rolePrefs.contains("$characterId:screen")
        val oldPaired = adbPrefs.getBoolean("paired_by_owner", false)
        val hadPaired = adbPrefs.contains("paired_by_owner")
        try {
            rolePrefs.edit().putBoolean("$characterId:screen", false).commit()
            adbPrefs.edit().putBoolean("paired_by_owner", false).commit()
            val status = CapabilityRegistry.phoneOperationContext(context, characterId)
            assertTrue(status.contains("background_phone（独立后台虚拟屏）工具已注册=true"))
            assertTrue(status.contains("本角色自主操作屏幕授权=false"))
            assertTrue(status.contains("本机无线调试已保存配对密钥=false"))
            assertTrue(status.contains("工具已注册"))
            assertFalse(status.contains("background_phone工具不存在"))
        } finally {
            val roleEdit = rolePrefs.edit()
            if (hadRole) roleEdit.putBoolean("$characterId:screen", oldRole)
            else roleEdit.remove("$characterId:screen")
            roleEdit.commit()
            val adbEdit = adbPrefs.edit()
            if (hadPaired) adbEdit.putBoolean("paired_by_owner", oldPaired)
            else adbEdit.remove("paired_by_owner")
            adbEdit.commit()
        }
    }

    @Test fun onlineScreenVisionMustNotDescribeMainScreenshotAsVirtualScreen() {
        val vision = CapabilityRegistry.screenVisionContext(context, characterId)
        assertTrue(vision.contains("capture_screen 只截主人当前可见的手机主屏"))
        assertTrue(vision.contains("必须用 background_phone"))
    }

    @Test fun pairingDoesNotPretendShellOrDisplayHasBeenVerified() {
        val rolePrefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val adbPrefs = context.getSharedPreferences("lulu_local_wireless_debug", Context.MODE_PRIVATE)
        val oldRole = rolePrefs.getBoolean("$characterId:screen", false)
        val hadRole = rolePrefs.contains("$characterId:screen")
        val oldPaired = adbPrefs.getBoolean("paired_by_owner", false)
        val hadPaired = adbPrefs.contains("paired_by_owner")
        try {
            rolePrefs.edit().putBoolean("$characterId:screen", true).commit()
            adbPrefs.edit().putBoolean("paired_by_owner", true).commit()
            val status = CapabilityRegistry.phoneOperationContext(context, characterId)
            assertTrue(status.contains("本角色自主操作屏幕授权=true"))
            assertTrue(status.contains("本机无线调试已保存配对密钥=true"))
            assertTrue(status.contains("已保存配对密钥≠本次已连接shell"))
            assertTrue(status.contains("action=tool,tool=background_phone"))
            assertTrue(status.contains("launch会在必要时先建立独立屏"))
            assertTrue(status.contains("\"appName\":\"已安装App的准确显示名称\""))
            assertTrue(status.contains("也可传准确packageName"))
            assertTrue(status.contains("sendToChat"))
            assertTrue(status.contains("tap_element"))
            assertTrue(status.contains("screenSnapshotId"))
            assertTrue(status.contains("elementRef"))
            assertTrue(status.contains("任何已安装App"))
            assertTrue(status.contains("小红书、抖音、网易云音乐、购物App"))
            // A device affordance isn't a script: both role-owned wishes and
            // owner-helpful choices are options even without a fresh shopping request.
            assertTrue(status.contains("不必等主人先提出代购任务"))
            assertTrue(status.contains("因自己的真实兴趣想收藏或拥有"))
            assertTrue(status.contains("想让主人买给自己也可以直接说出愿望"))
            assertTrue(status.contains("闲鱼、淘宝闪购、拼多多"))
            assertTrue(status.contains("程序不替你规定购物频率"))
            assertTrue(status.contains("未同意") || status.contains("主人已同意") == false)

            assertFalse(status.contains("read_xiaohongshu"))
            val capability = CapabilityRegistry.context(context, characterId)
            assertTrue("background search requires an explicit submit",
                capability.contains("type|submit|search|stop"))
            assertTrue("do not call typing a completed search",
                capability.contains("submit再由本人选择按后台屏回车提交搜索"))
            assertTrue(status.contains("绝不会把主屏截图当后台图"))
            assertTrue(status.contains("这不是capture_screen"))
            assertTrue(CapabilityRegistry.find("background_phone") != null)
        } finally {
            val roleEdit = rolePrefs.edit()
            if (hadRole) roleEdit.putBoolean("$characterId:screen", oldRole)
            else roleEdit.remove("$characterId:screen")
            roleEdit.commit()
            val adbEdit = adbPrefs.edit()
            if (hadPaired) adbEdit.putBoolean("paired_by_owner", oldPaired)
            else adbEdit.remove("paired_by_owner")
            adbEdit.commit()
        }
    }
}
