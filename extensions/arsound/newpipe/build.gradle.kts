import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// NewPipeExtractor with every dependency moved into its own package.
// SoundCloud already ships com.google.protobuf, so an unmoved copy would clash with it.
plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

val shade: Configuration by configurations.creating {
    configurations.getByName("compileClasspath").extendsFrom(this)
    configurations.getByName("runtimeClasspath").extendsFrom(this)
}

dependencies {
    shade(libs.newpipe.extractor)
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    val prefix = "app.arsound.shaded"
    relocate("org.schabi.newpipe", "$prefix.newpipe")
    relocate("com.grack.nanojson", "$prefix.nanojson")
    relocate("org.jsoup", "$prefix.jsoup")
    relocate("org.mozilla", "$prefix.mozilla")
    relocate("com.google.protobuf", "$prefix.protobuf")
    relocate("javax.annotation", "$prefix.javax.annotation")
    exclude("META-INF/**", "**/*.proto", "core/**")
    doLast { replaceNewerJavaCalls(archiveFile.get().asFile) }
}

/**
 * Calls to Java methods that Android has only since a later version than Arsound runs on (owner, name, descriptor),
 * rewritten into calls to app.arsound.shaded.compat.JavaCompat. Instance methods there take the object first.
 */
val newerJavaCalls = mapOf(
    Triple("java/util/stream/Collectors", "toUnmodifiableList", "()Ljava/util/stream/Collector;") to
        "()Ljava/util/stream/Collector;",
    Triple("java/net/URLEncoder", "encode", "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;") to
        "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;",
    Triple("java/net/URLDecoder", "decode", "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;") to
        "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;",
    Triple("java/math/BigInteger", "intValueExact", "()I") to "(Ljava/math/BigInteger;)I",
)

fun replaceNewerJavaCalls(jar: File) {
    val compat = "app/arsound/shaded/compat/JavaCompat"
    val rewritten = File(jar.path + ".tmp")
    var count = 0
    ZipFile(jar).use { input ->
        ZipOutputStream(rewritten.outputStream()).use { output ->
            for (entry in input.entries()) {
                var bytes = input.getInputStream(entry).use { it.readBytes() }
                if (entry.name.endsWith(".class") && !entry.name.startsWith(compat)) {
                    val reader = ClassReader(bytes)
                    val writer = ClassWriter(reader, 0)
                    var changed = false
                    reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitMethod(
                            access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
                        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                                val replacement = newerJavaCalls[Triple(owner, name, descriptor)]
                                if (replacement == null) {
                                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                } else {
                                    changed = true
                                    count++
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, compat, name, replacement, false)
                                }
                            }
                        }
                    }, 0)
                    if (changed) bytes = writer.toByteArray()
                }
                output.putNextEntry(ZipEntry(entry.name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
    jar.delete()
    rewritten.renameTo(jar)
    logger.lifecycle("Rewrote $count calls to Java methods newer than Android 12 in ${jar.name}")
}

configurations.named("runtimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false

    outgoing.artifacts.clear()
    outgoing.artifact(shadowJar)
}!!.let { artifacts { add(it.name, shadowJar) } }
