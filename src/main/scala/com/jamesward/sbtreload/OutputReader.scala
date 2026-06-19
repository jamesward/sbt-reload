package com.jamesward.sbtreload

import java.io.{ File, RandomAccessFile }
import java.nio.charset.StandardCharsets

/**
 * Pure, side-effect-light logic for incrementally reading a `runReload` capture file.
 *
 * Extracted from the plugin so the offset/truncation behavior of `reloadOutput`
 * can be unit-tested without standing up a forked JVM. The plugin keeps the
 * per-file [[OutputReader.ReadState]] in a static map and feeds it back in on the
 * next poll.
 */
private[sbtreload] object OutputReader:

  /**
   * What `reloadOutput` remembers between polls for a single capture file.
   *
   * @param epoch  the fork-restart generation we last read. `runReload` bumps a
   *               per-file epoch every time it (re)starts a fork (which truncates
   *               the capture file). When the epoch we see differs from the one in
   *               this state, the file was truncated since our last read and we must
   *               start over from byte 0 — even if the new file is already longer
   *               than [[offset]].
   * @param offset how many bytes of the current epoch we have already printed.
   */
  final case class ReadState(epoch: Long, offset: Long)

  object ReadState:
    val initial: ReadState = ReadState(epoch = 0L, offset = 0L)

  /**
   * Outcome of a single poll.
   *
   * @param lines   complete lines appended since the last poll (no trailing newline)
   * @param state   the state to remember for the next poll
   * @param message an optional status line to print when there were no new lines
   */
  final case class Result(lines: Vector[String], state: ReadState, message: Option[String])

  /**
   * Compute the new complete lines appended to `file` since `prev`.
   *
   * Truncation/restart detection is twofold:
   *
   *   1. If `epoch` differs from `prev.epoch`, the writer started a new fork since our
   *      last read (which truncates the capture file), so we restart from byte 0 — even
   *      if the new file is already LONGER than `prev.offset`. A length-only check
   *      (`prev.offset > len`) misses this case and reads from the middle of the new
   *      fork's output, dropping the beginning. That was the reported "I don't see the
   *      output after a reload" bug.
   *   2. As a backstop (covering the brief window between truncation and the epoch bump),
   *      if the file is now shorter than `prev.offset` we also restart from 0.
   */
  def poll(file: File, prev: ReadState, epoch: Long): Result =
    if !file.exists() then
      Result(Vector.empty, ReadState(epoch, 0L), Some(s"reloadOutput: no output captured yet (${file.getName})"))
    else
      val len = file.length()
      val start =
        if epoch != prev.epoch then 0L // a new fork truncated the file since our last read
        else if prev.offset > len then 0L // backstop: file shrank, so it was truncated
        else prev.offset
      if start >= len then Result(Vector.empty, ReadState(epoch, start), Some("reloadOutput: no new output"))
      else
        val raf = new RandomAccessFile(file, "r")
        val text =
          try
            raf.seek(start)
            val buf = new Array[Byte]((len - start).toInt)
            raf.readFully(buf)
            new String(buf, StandardCharsets.UTF_8)
          finally raf.close()
        val lastNl = text.lastIndexOf('\n')
        if lastNl < 0 then Result(Vector.empty, ReadState(epoch, start), Some("reloadOutput: no new output"))
        else
          val complete = text.substring(0, lastNl)
          val lines = if complete.isEmpty then Vector.empty else complete.linesIterator.toVector
          val consumed = text.substring(0, lastNl + 1).getBytes(StandardCharsets.UTF_8).length
          val message = if lines.isEmpty then Some("reloadOutput: no new output") else None
          Result(lines, ReadState(epoch, start + consumed), message)
end OutputReader
