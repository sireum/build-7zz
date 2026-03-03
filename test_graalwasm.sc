#!/usr/bin/env -S scala-cli shebang
//> using scala 2.13
//> using jvm graalvm-community:25.0.2
//> using javaOpt --enable-native-access=ALL-UNNAMED
//> using dep org.graalvm.polyglot:polyglot:25.0.2
//> using dep org.graalvm.wasm:wasm-language:25.0.2
//> using dep org.graalvm.truffle:truffle-runtime:25.0.2
// Test 7zz_graal.wasm via GraalWasm (in-process).
//
// Usage:
//     scala-cli test_graalwasm.sc -- [7zz_graal.wasm]

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import org.graalvm.polyglot.{Context, Source, PolyglotException}
import org.graalvm.polyglot.io.ByteSequence

// --- Config ---

val scriptDir = {
  val p = Paths.get(sys.props.getOrElse("script.dir",
    sys.props.getOrElse("user.dir", ".")))
  p.toAbsolutePath
}
val wasmPath = if (args.nonEmpty) Paths.get(args(0)).toAbsolutePath
               else scriptDir.resolve("7zz_graal.wasm")

if (!Files.exists(wasmPath)) {
  System.err.println(s"WASM binary not found: $wasmPath")
  sys.exit(1)
}

val wasmBytes = Files.readAllBytes(wasmPath)
println(s"Binary: $wasmPath (${wasmBytes.length / 1024} KiB)")

// --- Runner ---

case class RunResult(exitCode: Int, stdout: String, stderr: String)

def run7zz(workDir: Path, cmdArgs: String*): RunResult = {
  val stdout = new ByteArrayOutputStream()
  val stderr = new ByteArrayOutputStream()
  val source = Source.newBuilder("wasm",
    ByteSequence.create(wasmBytes), "7zz").build()
  val allArgs = ("7zz" +: cmdArgs).toArray
  // WasiMapDirs: preopen root (/) and workDir for full filesystem access
  val context = Context.newBuilder("wasm")
    .option("wasm.Builtins", "wasi_snapshot_preview1")
    .option("wasm.WasiMapDirs", s"/::/,${workDir.toAbsolutePath}::${workDir.toAbsolutePath}")
    .option("engine.WarnInterpreterOnly", "false")
    .arguments("wasm", allArgs)
    .in(System.in)
    .out(stdout)
    .err(stderr)
    .currentWorkingDirectory(workDir)
    .allowAllAccess(true)
    .build()

  var exitCode = 0
  try {
    val module = context.eval(source)
    // GraalWasm 25.x: for WASI modules, eval() auto-calls _start.
    // The canInstantiate check handles older API where explicit call is needed.
    if (module.canInstantiate) {
      val instance = module.newInstance()
      val exports = instance.getMember("exports")
      exports.getMember("_start").executeVoid()
    }
  } catch {
    case e: PolyglotException if e.isExit =>
      exitCode = e.getExitStatus
    case e: Throwable =>
      exitCode = -1
      stderr.write(s"Exception: ${e.getClass.getName}: ${e.getMessage}\n"
        .getBytes(StandardCharsets.UTF_8))
  } finally {
    try { context.close() } catch { case _: Throwable => }
  }

  RunResult(exitCode,
    stdout.toString(StandardCharsets.UTF_8),
    stderr.toString(StandardCharsets.UTF_8))
}

// --- Test helpers ---

var passed = 0
var failed = 0

def test(label: String)(body: => Boolean): Unit = {
  print(s"  $label ... ")
  val t0 = System.nanoTime()
  try {
    if (body) {
      val ms = (System.nanoTime() - t0) / 1000000L
      println(s"PASS  (${ms}ms)")
      passed += 1
    } else {
      println("FAIL")
      failed += 1
    }
  } catch {
    case e: Throwable =>
      println(s"FAIL (${e.getMessage})")
      failed += 1
  }
}

// --- Tests ---

val tmpDir = Files.createTempDirectory("7zz_graalwasm_test")
val testFile = tmpDir.resolve("hello.txt")
val archivePath = tmpDir.resolve("test.7z")
val extractDir = tmpDir.resolve("extracted")
Files.createDirectories(extractDir)
Files.write(testFile, "Hello from GraalWasm 7zz test!\n".getBytes(StandardCharsets.UTF_8))

println("\n=== Test: 7zz via GraalWasm ===")

test("Help output") {
  val r = run7zz(tmpDir)
  r.stdout.contains("7-Zip") && r.stdout.contains("Usage:")
}

test("Archive creation") {
  val r = run7zz(tmpDir, "a", archivePath.toString, testFile.toString)
  r.exitCode == 0 && r.stdout.contains("Everything is Ok")
}

test("Archive listing") {
  val r = run7zz(tmpDir, "l", archivePath.toString)
  r.exitCode == 0 && r.stdout.contains("hello.txt")
}

test("Archive extraction") {
  val r = run7zz(tmpDir, "x", archivePath.toString,
    s"-o${extractDir.toAbsolutePath}")
  r.exitCode == 0 && r.stdout.contains("Everything is Ok")
}

test("Extracted content matches") {
  val extracted = extractDir.resolve("hello.txt")
  Files.exists(extracted) &&
    new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8) ==
      "Hello from GraalWasm 7zz test!\n"
}

test("Round-trip with multi-file archive") {
  val rtDir = tmpDir.resolve("roundtrip")
  val rtExtract = tmpDir.resolve("rt_extract")
  val rtArchive = tmpDir.resolve("roundtrip.7z")
  Files.createDirectories(rtDir)
  Files.createDirectories(rtExtract)
  for (i <- 1 to 5) {
    Files.write(rtDir.resolve(s"file_$i.txt"),
      s"Content of file $i\n${(1 to i * 100).mkString(",")}\n"
        .getBytes(StandardCharsets.UTF_8))
  }
  val ra = run7zz(tmpDir, "a", rtArchive.toString,
    s"${rtDir.toAbsolutePath}/*")
  if (ra.exitCode != 0) false
  else {
    val rx = run7zz(tmpDir, "x", rtArchive.toString,
      s"-o${rtExtract.toAbsolutePath}")
    if (rx.exitCode != 0) false
    else {
      (1 to 5).forall { i =>
        val orig = Files.readAllBytes(rtDir.resolve(s"file_$i.txt"))
        val ext = rtExtract.resolve(s"file_$i.txt")
        Files.exists(ext) && java.util.Arrays.equals(orig,
          Files.readAllBytes(ext))
      }
    }
  }
}

// --- Summary ---

println(s"\n$passed passed, $failed failed")

// Cleanup
def deleteRecursive(p: Path): Unit = {
  if (Files.isDirectory(p))
    Files.list(p).forEach(deleteRecursive)
  Files.deleteIfExists(p)
}
deleteRecursive(tmpDir)

sys.exit(if (failed == 0) 0 else 1)
