package eu.glatz.tomeyreader

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Point
import java.awt.image.*
import java.io.File
import java.io.FileFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.experimental.or
import kotlin.math.ceil


class FileReader(private val settings: Settings, private val fileSettings: FileTagSettings, private val postProcessor: PostProcessor? = null) {

    private val logger: Logger = LoggerFactory.getLogger(this.javaClass)

    fun run() {
        logger.info("Loading files form ${settings.getAbsoluteDataFolder.path} with extension ${settings.fileExtensions}")
        val files = getFiles(settings.getAbsoluteDataFolder, settings.fileExtensions)
        if (validateInputOutput(files))
            readFiles(files)
    }

    private fun readFiles(files: Array<File>) {
        files.forEach { readFile(it) }
    }

    private fun readFile(file: File) {
        logger.info("Processing file: ${file.path}")

        val fileSize = Settings.toMByte(file.length())
        val freeMem = Settings.toMByte(settings.freeMemory)

//        logger.info("Free memory: ${freeMem} MB, file size: ${fileSize} MB")
//
//        if(freeMem < fileSize)
//            throw IllegalStateException("Not enough free memory (Free memory: ${freeMem} MB, file size: ${fileSize} MB), please increase memory (-Xm1024m ")
        val bytes = readByteArray(file)
        val imageSettings = getImageSettings(bytes)

        if (imageSettings == null) {
            logger.error("Could not read or initialize Image settings for ${file.path}")
            return
        } else {
            logger.info("Image settings for ${file.path} read")
        }

        imageSettings.print()

        // oct images
        if (settings.mode >= 2) {

            val copyArray = ByteArray(imageSettings.imageSize)
            val imageArray = ShortArray(copyArray.size / imageSettings.bytesPerPixel)
            val resultIMG = BufferedImage(imageSettings.xResolution, imageSettings.yResolution, BufferedImage.TYPE_USHORT_GRAY)

            var imageCount = 0
            var imageOffset = imageSettings.startOffset

            logger.info("Exporting vaa-images!")

            val resultImgs = mutableListOf<File>()

            while ((imageOffset + imageSettings.imageSize) < bytes.size && imageCount < imageSettings.imageCount) {

                logger.info("Reading img ${imageCount + 1} of ${imageSettings.imageCount} from file: ${file.path}")
                val imageBytes = readNextImage(imageOffset, imageSettings.imageSize, bytes, copyArray)
                byteArrayToImage(imageBytes, imageArray, resultIMG, imageSettings.bytesPerPixel)

                val targetFile = getTargetFile(imageCount.toString(), file, ".${fileSettings.targetImageFormat}")
                resultImgs.add(targetFile)

                writeImage(targetFile, resultIMG)

                imageOffset += imageSettings.imageSize
                imageCount++
            }

            postProcessor?.run(resultImgs.toTypedArray(), file)
        }

        // patient info
        if (settings.mode >= 3 || settings.mode == 0) {
            logger.info("Exporting patient infos!")
            val infoFile = File(settings.getAbsoluteTargetFolder, file.name.substringBeforeLast(".") + ".json")
            ObjectMapper().writeValue(infoFile, imageSettings)
        }

        // eye images
        if (settings.mode >= 3 || settings.mode == 1) {
            logger.info("Exporting eye-images!")
            val headers = readEyeImageHeaders(bytes, imageSettings.eyeImageStartOffset)
            logger.info("${headers.size} eye-images found!")


            if (headers.size > 0) {
                var copyArray = ByteArray(headers[0].imageSize)
                var resultIMG = BufferedImage(headers[0].width, headers[0].height, BufferedImage.TYPE_BYTE_GRAY)

                var eyeStartOffset = imageSettings.eyeImageStartOffset + fileSettings.eyeImageHeaderSize * headers.size

                println(eyeStartOffset)

                var headerCount = 0

                for (header in headers) {
                    if (header.imageSize != copyArray.size) {
                        copyArray = ByteArray(header.imageSize)
                        resultIMG = BufferedImage(headers[0].width, headers[0].height, BufferedImage.TYPE_BYTE_GRAY)
                    }

                    if (eyeStartOffset + fileSettings.eyeImageContentOffset+header.imageSize > bytes.size){
                        logger.error("Could not read all eye-images, file size to small!")
                        return
                    }

                    val imageBytes = readNextImage(eyeStartOffset + fileSettings.eyeImageContentOffset, header.imageSize, bytes, copyArray)
                    val max = imageBytes.max() ?: 255.toByte()

                    // windowing
                    imageBytes.forEachIndexed { index, b ->
                        if (b < 0)
                            imageBytes[index] = max
                    }

                    resultIMG.data = Raster.createRaster(resultIMG.sampleModel, DataBufferByte(copyArray, copyArray.size) as DataBuffer, Point())

                    val targetFile = getTargetFile("eye-image-$headerCount", file, ".${fileSettings.targetImageFormat}")

                    writeImage(targetFile, resultIMG)

                    headerCount++
                    eyeStartOffset += fileSettings.eyeImageContentOffset + header.imageSize
                }
            }
        }

        System.gc()
    }

    private fun getImageSettings(byteContent: ByteArray): ImageSettings? {
        try {
            val result = ImageSettings()
            result.xResolution = if (settings.xResolution == -1) findTag(fileSettings.width, fileSettings.lineBreak, byteContent).second.toInt() else settings.xResolution
            result.yResolution = if (settings.yResolution == -1) findTag(fileSettings.height, fileSettings.lineBreak, byteContent).second.toInt() else settings.yResolution
            result.imageCount = if (settings.imageCount == -1) findTag(fileSettings.imageCount, fileSettings.lineBreak, byteContent).second.toInt() else settings.imageCount
            result.bytesPerPixel = if (settings.bytesPerPixel == -1) {
                val bitsPerPixel = findTag(fileSettings.bitsPerPixel, fileSettings.lineBreak, byteContent).second.toDouble()
                (ceil(bitsPerPixel / 8)).toInt()
            } else settings.bytesPerPixel

            val xWidthInMM = findTag(fileSettings.xSizeInMM, fileSettings.lineBreak, byteContent).second.toDouble()
            val yWithInMm = findTag(fileSettings.ySizeInMM, fileSettings.lineBreak, byteContent).second.toDouble()
            result.xMMperPixel = xWidthInMM / result.xResolution
            // if images is 90° rotated
            result.yMMperPixel = yWithInMm / result.xResolution
            result.zMMperPixel = findTag(fileSettings.zSizePerPixel, fileSettings.lineBreak, byteContent).second.toDouble()
            result.examinationDate = findTag(fileSettings.examinationDate, fileSettings.lineBreak, byteContent).second
            result.examinationTime = findTag(fileSettings.examinationTime, fileSettings.lineBreak, byteContent).second
            result.patient.id = findTag(fileSettings.patient.id, fileSettings.lineBreak, byteContent).second
            result.patient.firstName = findTag(fileSettings.patient.firstName, fileSettings.lineBreak, byteContent).second
            result.patient.lastName = findTag(fileSettings.patient.lastName, fileSettings.lineBreak, byteContent).second
            result.patient.birthday = findTag(fileSettings.patient.birthday, fileSettings.lineBreak, byteContent).second
            result.patient.eye = findTag(fileSettings.patient.eye, fileSettings.lineBreak, byteContent).second
            result.patient.commentary = findTag(fileSettings.patient.commentary, fileSettings.lineBreak, byteContent).second

            result.fileName1 = findTag(fileSettings.fileName, fileSettings.lineBreak, byteContent).second
            result.fileName2 = findTag(fileSettings.fileName2, fileSettings.lineBreak, byteContent).second

            result.startOffset = if (settings.startOffset == -1) findImageStart(byteContent, result.fileName1) else settings.startOffset
            result.eyeImageStartOffset = findEyeImageStart(byteContent, result.fileName2, result.startOffset)

            return result
        } catch (e: Exception) {
            logger.error("Error reading settings: ${e.message}")
            return null
        }
    }

    private fun validateInputOutput(files: Array<File>): Boolean {
        logger.info("Found ${files.size} files")

        if (files.isNullOrEmpty()) {
            logger.error("No Files to process found!")
            return false
        }

        val target = settings.getAbsoluteTargetFolder

        if (!target.exists())
            target.mkdirs()
        else if (!target.isDirectory) {
            logger.error("Target (${target.path}) is no folder")
            return false
        }

        logger.info("Files ok..")

        return true
    }

    /**
     * Read headers for eye images
     */
    private fun readEyeImageHeaders(byteContent: ByteArray, startOffset: Int): List<ImageSettings.EyeImage> {
        var imageCount = 0
        val headers = mutableListOf<ImageSettings.EyeImage>()

        val tmp = ByteArray(4)
        val bb: ByteBuffer = ByteBuffer.wrap(tmp)
        bb.order(ByteOrder.LITTLE_ENDIAN)

        do {
            System.arraycopy(byteContent, startOffset + fileSettings.eyeImageHeaderSize * imageCount + fileSettings.eyeImageInHeaderHeightPosition, tmp, 0, 4)
            val height = bb.getInt(0)

            System.arraycopy(byteContent, startOffset + fileSettings.eyeImageHeaderSize * imageCount + fileSettings.eyeImageInHeaderWidthPosition, tmp, 0, 4)
            val width = bb.getInt(0)

            headers.add(ImageSettings.EyeImage().apply { this.height = height; this.width = width })

            imageCount++

        } while (byteContent[startOffset + fileSettings.eyeImageHeaderSize * imageCount] == 6.toByte())

        return headers
    }

    private fun readByteArray(file: File): ByteArray {
        return Files.readAllBytes(file.toPath())
    }

    private fun findImageStart(byteContent: ByteArray, fileName: String): Int {
        val imageOffset = findOffset(fileName + fileSettings.nullChar + fileSettings.nullChar, byteContent)
        if (imageOffset != -1)
            return imageOffset + fileSettings.imageOffset - 1
        else
            throw IllegalStateException("Image Offset not fount!")
    }

    private fun findEyeImageStart(byteContent: ByteArray, fileName: String, start_offset: Int = 0): Int {
        val offset = findOffset(fileSettings.escChar + fileName, byteContent, start_offset)
        if (offset != -1)
            return offset + fileSettings.eyeImageFirstHeaderOffset
        else
            throw IllegalStateException("Eye-Image Offset not fount!")
    }

    private fun findTag(prefix: String, suffix: String, byteContent: ByteArray, startOffset: Int = 0): Pair<Int, String> {
        val tagIndex = findOffset(prefix, byteContent, startOffset)

        require(tagIndex != -1) { "Tag not Found: ${prefix}" }

        val tagContent = mutableListOf<Byte>()
        var suffixCount = 0

        for (i in tagIndex + 1 until byteContent.size) {
            if (byteContent[i].toChar() == suffix[suffixCount]) {
                if (suffixCount == suffix.length - 1) {
                    return Pair(i, String(tagContent.toByteArray(), Charset.forName("Cp1252")))
                }

                suffixCount++
            } else {
                suffixCount = 0
                tagContent.add(byteContent[i])
            }
        }

        return Pair(-1, String(tagContent.toByteArray(), Charset.forName("Cp1252")))
    }

    private fun findOffset(searchTag: String, byteContent: ByteArray, startOffset: Int = 0): Int {
        var searchOffsetCount = 0

        for (i in startOffset until byteContent.size) {
            if (byteContent[i].toChar() == searchTag[searchOffsetCount]) {
                if (searchOffsetCount == searchTag.length - 1) {
                    return i
                }
                searchOffsetCount++
            } else {
                searchOffsetCount = 0
            }
        }

        return -1
    }

    private fun getTargetFile(fileName: String, file: File, newExtension: String): File {
        var targetFolder = settings.getAbsoluteTargetFolder

        if (settings.createNewDirForFile)
            targetFolder = File(targetFolder, file.name.substringBeforeLast("."))

        if (!targetFolder.exists())
            targetFolder.mkdirs()

        return File(targetFolder, file.name.substringBeforeLast(".") + "-" + fileName + newExtension)
    }

    private fun readNextImage(offset: Int, size: Int, bytes: ByteArray, copyArray: ByteArray): ByteArray {
        System.arraycopy(bytes, offset, copyArray, 0, size)
        return copyArray
    }

    private fun byteArrayToImage(copyArray: ByteArray, targetArray: ShortArray, img: BufferedImage, bytesPerPixel: Int): BufferedImage {

        var byteArrayCounter = 0
        var byteArrayEmpty = false

        for (i in 0 until targetArray.size) {
            targetArray[i] = 0

            if (!byteArrayEmpty)
                for (y in 0 until bytesPerPixel) {
                    if (byteArrayCounter >= copyArray.size) {
                        byteArrayEmpty = true
                        break
                    }

                    targetArray[i] = targetArray[i].or((copyArray[byteArrayCounter].toUByte().toInt() shl y * 7).toShort())
                    byteArrayCounter++
                }

        }

        img.data = Raster.createRaster(img.sampleModel, DataBufferUShort(targetArray, targetArray.size) as DataBuffer, Point())

        return img
    }

    private fun writeImage(target: File, img: BufferedImage) {
        ImageIO.write(img, "png", target)
    }

    companion object {
        fun getFiles(baseFolder: File, fileExtension: String): Array<File> {
            return baseFolder.listFiles { x -> x.name.endsWith(fileExtension) } ?: emptyArray()
        }
    }


    class ImageSettings {

        private val logger: Logger = LoggerFactory.getLogger(this.javaClass)

        var fileName1: String = ""

        var fileName2: String = ""

        var xResolution = 0

        var yResolution = 0

        var imageCount = 0

        var bytesPerPixel = 0

        var startOffset = 0

        var eyeImageStartOffset = 0

        /**
         * Image width mm per pixel
         */
        var xMMperPixel: Double = 0.0

        /**
         * Image width Y, not used
         */
        var yMMperPixel: Double = 0.0

        /**
         * Image height mm per pixel
         */
        var zMMperPixel: Double = 0.0

        var examinationDate: String = ""

        var examinationTime: String = ""

        /**
         * Patient
         */
        val patient = Patient()

        val imageSize: Int
            get() = xResolution * yResolution * bytesPerPixel

        fun print() {
            logger.info("Image settings")
            logger.info("xRes = $xResolution")
            logger.info("yRes = $yResolution")
            logger.info("imageCount = $imageCount")
            logger.info("bytesPerPixel = $bytesPerPixel")
            logger.info("startOffset = $startOffset")
            logger.info("startOffset_EyeImage = $eyeImageStartOffset")
            logger.info("patient.name = ${patient.lastName}")
            logger.info("patient.surname = ${patient.firstName}")
        }

        class Patient {
            var id: String = ""
            var firstName: String = ""
            var lastName: String = ""
            var birthday: String = ""
            var eye: String = ""
            var commentary: String = ""
        }

        class EyeImage {
            var width = 0
            var height = 0

            // only one byte per pixel
            val imageSize: Int
                get() = width * height
        }
    }
}
