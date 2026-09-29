package com.niki914.zafiro.repo

import android.content.pm.PackageInstaller
import com.niki914.zafiro.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * v2.1.2 in-app self-update 的纯逻辑部分：
 * - APK asset 按设备 ABI 选取
 * - PackageInstaller 失败码 → 用户可读文案映射
 * - 下载失败分类
 */
class UpdateInstallerTest {

    // region pickApkAssetName

    @Test
    fun `arm64 device prefers arm64 asset`() {
        val picked = pickApkAssetName(
            abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            assetNames = listOf("zafiro-v2.1.2-universal.apk", "zafiro-v2.1.2-arm64.apk"),
        )
        assertEquals("zafiro-v2.1.2-arm64.apk", picked)
    }

    @Test
    fun `32bit device prefers universal asset`() {
        val picked = pickApkAssetName(
            abis = listOf("armeabi-v7a", "armeabi"),
            assetNames = listOf("zafiro-v2.1.2-universal.apk", "zafiro-v2.1.2-arm64.apk"),
        )
        assertEquals("zafiro-v2.1.2-universal.apk", picked)
    }

    @Test
    fun `x86 emulator falls back to universal`() {
        val picked = pickApkAssetName(
            abis = listOf("x86_64", "x86"),
            assetNames = listOf("zafiro-v2.1.2-universal.apk", "zafiro-v2.1.2-arm64.apk"),
        )
        assertEquals("zafiro-v2.1.2-universal.apk", picked)
    }

    @Test
    fun `arm64 device falls back to universal when arm64 asset missing`() {
        val picked = pickApkAssetName(
            abis = listOf("arm64-v8a"),
            assetNames = listOf("zafiro-v2.1.2-universal.apk"),
        )
        assertEquals("zafiro-v2.1.2-universal.apk", picked)
    }

    @Test
    fun `non-apk assets are ignored`() {
        val picked = pickApkAssetName(
            abis = listOf("arm64-v8a"),
            assetNames = listOf("release.zip", "NOTES.md", "zafiro-v2.1.2-arm64.apk"),
        )
        assertEquals("zafiro-v2.1.2-arm64.apk", picked)
    }

    @Test
    fun `falls back to any apk when preferred keyword missing`() {
        val picked = pickApkAssetName(
            abis = listOf("armeabi-v7a"),
            assetNames = listOf("zafiro-v2.1.2-arm64.apk"),
        )
        assertEquals("zafiro-v2.1.2-arm64.apk", picked)
    }

    @Test
    fun `no apk assets yields null`() {
        val picked = pickApkAssetName(
            abis = listOf("arm64-v8a"),
            assetNames = listOf("release.zip", "NOTES.md"),
        )
        assertEquals(null, picked)
    }

    @Test
    fun `empty asset list yields null`() {
        assertEquals(null, pickApkAssetName(listOf("arm64-v8a"), emptyList()))
    }

    // endregion

    // region mapInstallFailure

    @Test
    fun `incompatible maps to signature guidance`() {
        assertEquals(
            R.string.update_dialog_fail_incompatible,
            UpdateInstaller.mapInstallFailure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE),
        )
    }

    @Test
    fun `storage failure maps to storage message`() {
        assertEquals(
            R.string.update_dialog_fail_storage,
            UpdateInstaller.mapInstallFailure(PackageInstaller.STATUS_FAILURE_STORAGE),
        )
    }

    @Test
    fun `blocked failure maps to blocked message`() {
        assertEquals(
            R.string.update_dialog_fail_blocked,
            UpdateInstaller.mapInstallFailure(PackageInstaller.STATUS_FAILURE_BLOCKED),
        )
    }

    @Test
    fun `invalid failure maps to corrupted-file message`() {
        assertEquals(
            R.string.update_dialog_fail_invalid,
            UpdateInstaller.mapInstallFailure(PackageInstaller.STATUS_FAILURE_INVALID),
        )
    }

    @Test
    fun `conflict failure maps to conflict message`() {
        assertEquals(
            R.string.update_dialog_fail_conflict,
            UpdateInstaller.mapInstallFailure(PackageInstaller.STATUS_FAILURE_CONFLICT),
        )
    }

    @Test
    fun `aborted failure maps to aborted message`() {
        assertEquals(
            R.string.update_dialog_fail_aborted,
            UpdateInstaller.mapInstallFailure(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
    }

    @Test
    fun `unknown failure maps to generic message`() {
        assertEquals(
            R.string.update_dialog_fail_generic,
            UpdateInstaller.mapInstallFailure(-12345),
        )
    }

    // endregion

    // region mapDownloadFailure

    @Test
    fun `http 4xx maps to corrupted-file guidance`() {
        assertEquals(
            R.string.update_dialog_fail_invalid,
            UpdateInstaller.mapDownloadFailure(IOException("HTTP 404")),
        )
    }

    @Test
    fun `user cancel maps to aborted`() {
        assertEquals(
            R.string.update_dialog_fail_aborted,
            UpdateInstaller.mapDownloadFailure(IOException("cancelled")),
        )
    }

    @Test
    fun `io failure maps to network guidance`() {
        assertEquals(
            R.string.update_dialog_fail_network,
            UpdateInstaller.mapDownloadFailure(IOException("connection reset")),
        )
    }

    // endregion

    @Test
    fun `min apk size sanity`() {
        // 真实包 29-48MB；下限必须远大于任何误下的小文件（HTML 错误页等）
        assertTrue(UpdateInstaller.MIN_APK_BYTES > 5L * 1024 * 1024)
    }
}
