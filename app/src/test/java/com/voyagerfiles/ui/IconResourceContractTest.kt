package com.voyagerfiles.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest

class IconResourceContractTest {

    @Test
    fun adaptiveIconsUseDedicatedMonochromeArtwork() {
        val repositoryRoot = repositoryRoot()
        val monochrome = File(repositoryRoot, "app/src/main/res/drawable/ic_launcher_monochrome.xml")
        assertTrue("Dedicated monochrome icon is missing", monochrome.isFile)

        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val adaptiveIcon = File(repositoryRoot, "app/src/main/res/mipmap-anydpi-v26/$name").readText()
            assertTrue("$name must use the dedicated monochrome icon", adaptiveIcon.contains("@drawable/ic_launcher_monochrome"))
        }
    }

    @Test
    fun approvedLauncherAndStoreAssetsAreInstalledExactly() {
        val repositoryRoot = repositoryRoot()
        approvedHashes.forEach { (path, expectedHash) ->
            val file = File(repositoryRoot, path)
            assertTrue("Approved icon asset is missing: $path", file.isFile)
            assertEquals("Approved icon asset changed: $path", expectedHash, sha256(file))
        }
    }

    @Test
    fun rasterFallbacksAndStoreIconHaveRequiredDimensions() {
        val repositoryRoot = repositoryRoot()
        val densities = mapOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192,
        )
        densities.forEach { (density, expectedSize) ->
            listOf("ic_launcher.png", "ic_launcher_round.png").forEach { name ->
                assertSquarePng(
                    file = File(repositoryRoot, "app/src/main/res/mipmap-$density/$name"),
                    expectedSize = expectedSize,
                )
            }
        }
        assertSquarePng(
            file = File(repositoryRoot, "fastlane/metadata/android/en-US/images/icon.png"),
            expectedSize = 512,
        )
    }

    private fun assertSquarePng(file: File, expectedSize: Int) {
        val (width, height) = DataInputStream(file.inputStream()).use { input ->
            assertEquals("Unexpected PNG header for ${file.path}", 16, input.skipBytes(16))
            input.readInt() to input.readInt()
        }
        assertEquals("Unexpected width for ${file.path}", expectedSize, width)
        assertEquals("Unexpected height for ${file.path}", expectedSize, height)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory, checkNotNull(workingDirectory.parentFile))
            .first { File(it, "app/src/main/res").isDirectory && File(it, "fastlane").isDirectory }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val approvedHashes = mapOf(
            "app/src/main/res/drawable/ic_launcher_background.xml" to "050aaa60959707bf93881e30988ac33c591f29a5e2550d9533f2c4722ba6f4ec",
            "app/src/main/res/drawable/ic_launcher_foreground.xml" to "37988bdbc7d663316685ea6e72ea8c669b4a41d680b3f6cdbce2ff82dd2f901a",
            "app/src/main/res/drawable/ic_launcher_monochrome.xml" to "74107e2dd0ba5de423fec028b6d8fca456741ab05b9aa639bd48659ff3ae7afb",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" to "ada31da9e23f4520f3753e71ca23799a107431473daa3774b6cecac26847260c",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml" to "ada31da9e23f4520f3753e71ca23799a107431473daa3774b6cecac26847260c",
            "app/src/main/res/mipmap-hdpi/ic_launcher.png" to "b96d5ddbdadf2b370ee5df049420624460a4b2e84f075a4d8a5677169c65f819",
            "app/src/main/res/mipmap-hdpi/ic_launcher_round.png" to "e1f02e988530075b89f26012b5cd30ed1d9ae1896fd2e1c4adc7206c464bb3a9",
            "app/src/main/res/mipmap-mdpi/ic_launcher.png" to "2e8dc6d6c0d609546229e32dbf7ab7a10fb15fa9a090a8abd347dcd236131163",
            "app/src/main/res/mipmap-mdpi/ic_launcher_round.png" to "631237135b7cacc44fcbfce8e87190b8dd4ffb624b3bab7966df9e62bb56ec5b",
            "app/src/main/res/mipmap-xhdpi/ic_launcher.png" to "08ecd436c4090d1a274298a1775f90416c3de809a93450197ba0f3d11f04755a",
            "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png" to "6fcdf9b371d5f3549cc89a4546ae47cf54d6052781b1079132f7fc0344bbc1d9",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher.png" to "19e18b63453b48090169a6a5b0981b6dc8c5bb013f9a20cae9ddb622202e7101",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png" to "6f13a7d22c8b27b09a1a96d67e58d0b3e695fba794e0c7a2b12cfae6237cc7b0",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" to "fa8077c48d3cbc2f759303668243bca9e7e627a86c9a14d3bb76155376832f68",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" to "ead980c83bc44cddc6595053137deee24dfc91aeb774d0f74159664995c42405",
            "fastlane/metadata/android/en-US/images/icon.png" to "73cb88c01bc366cb8f793e904f909cb6f8d479a4ceedd3d0f72b85577e6f61f4",
        )
    }
}
