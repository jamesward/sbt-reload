package com.jamesward.sbtreload

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import OutputReader.{ ReadState, Result }

class OutputReaderSuite extends munit.FunSuite:

  /** A fresh temp capture file per test, cleaned up afterwards. */
  private val captureFile = FunFixture[File](
    setup = _ => Files.createTempFile("reload-output", ".log").toFile,
    teardown = f => { f.delete(); () },
  )

  /** Overwrite the whole capture file (simulates `runReload` truncating on (re)start). */
  private def truncateTo(f: File, content: String): Unit =
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))

  /** Append to the capture file (simulates the running fork emitting more lines). */
  private def append(f: File, content: String): Unit =
    Files.write(
      f.toPath,
      content.getBytes(StandardCharsets.UTF_8),
      java.nio.file.StandardOpenOption.APPEND,
    )

  captureFile.test("first poll prints all complete lines from the start") { f =>
    truncateTo(f, "alpha\nbravo\n")
    val r = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    assertEquals(r.lines, Vector("alpha", "bravo"))
    assertEquals(r.message, None)
  }

  captureFile.test("second poll within the same fork only prints newly appended lines") { f =>
    truncateTo(f, "alpha\nbravo\n")
    val r1 = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    append(f, "charlie\n")
    val r2 = OutputReader.poll(f, r1.state, epoch = 1L)
    assertEquals(r2.lines, Vector("charlie"))
    assertEquals(r2.message, None)
  }

  captureFile.test("a partial trailing line is held back until its newline arrives") { f =>
    truncateTo(f, "alpha\npartial")
    val r1 = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    assertEquals(r1.lines, Vector("alpha"))
    append(f, "-rest\n")
    val r2 = OutputReader.poll(f, r1.state, epoch = 1L)
    assertEquals(r2.lines, Vector("partial-rest"))
  }

  captureFile.test("no new output reports a message and no lines") { f =>
    truncateTo(f, "alpha\n")
    val r1 = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    val r2 = OutputReader.poll(f, r1.state, epoch = 1L)
    assertEquals(r2.lines, Vector.empty)
    assertEquals(r2.message, Some("reloadOutput: no new output"))
  }

  // --- The bug the user reported: output goes missing after a ~runReload restart ---
  //
  // After a restart the capture file is truncated and the NEW fork writes more bytes
  // than the previous read offset before the next `reloadOutput` poll. A length-only
  // truncation check cannot tell this apart from normal appended output, so the read
  // starts in the middle of the new fork's output and the beginning is lost.
  captureFile.test("restart: new fork output longer than prior offset is shown in full") { f =>
    // Fork #1 emits a long-ish line; reloadOutput consumes it (offset advances).
    truncateTo(f, "FORK1: a fairly long startup banner line\n")
    val r1 = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    assertEquals(r1.lines, Vector("FORK1: a fairly long startup banner line"))

    // ~runReload restarts: the file is TRUNCATED (epoch bumps to 2) and fork #2
    // writes a SHORT line — fewer bytes than fork #1's offset.
    truncateTo(f, "FORK2: hi\n")
    val r2 = OutputReader.poll(f, r1.state, epoch = 2L)
    assertEquals(r2.lines, Vector("FORK2: hi"), "short restart output must be shown from the start")
  }

  captureFile.test("restart: new fork output is shown even when it is LONGER than the prior offset") { f =>
    // Fork #1 emits a short line.
    truncateTo(f, "FORK1: hi\n")
    val r1 = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    assertEquals(r1.lines, Vector("FORK1: hi"))

    // Restart: truncate + a LONGER first line than fork #1 produced. With a length-only
    // truncation check (prev.offset <= len) the reset is missed and the head is dropped.
    truncateTo(f, "FORK2: a much longer first line than before\nFORK2: second\n")
    val r2 = OutputReader.poll(f, r1.state, epoch = 2L)
    assertEquals(
      r2.lines,
      Vector("FORK2: a much longer first line than before", "FORK2: second"),
      "the entire restarted fork's output must be shown, not a mid-line fragment",
    )
  }

  captureFile.test("incremental reads still work after a restart reset") { f =>
    truncateTo(f, "FORK1: hello\n")
    val r1 = OutputReader.poll(f, ReadState.initial, epoch = 1L)
    truncateTo(f, "FORK2: one\n")
    val r2 = OutputReader.poll(f, r1.state, epoch = 2L)
    assertEquals(r2.lines, Vector("FORK2: one"))
    append(f, "FORK2: two\n")
    val r3 = OutputReader.poll(f, r2.state, epoch = 2L)
    assertEquals(r3.lines, Vector("FORK2: two"))
  }

  test("missing capture file reports a friendly message") {
    val missing = new File("/tmp/definitely-not-a-real-reload-capture-file-xyz.log")
    val r = OutputReader.poll(missing, ReadState.initial, epoch = 0L)
    assertEquals(r.lines, Vector.empty)
    assert(r.message.exists(_.contains("no output captured yet")))
  }

end OutputReaderSuite
