/*
 *   __   __     __  __     __         __
 *  /\ "-.\ \   /\ \/\ \   /\ \       /\ \
 *  \ \ \-.  \  \ \ \_\ \  \ \ \____  \ \ \____
 *   \ \_\\"\_\  \ \_____\  \ \_____\  \ \_____\
 *    \/_/ \/_/   \/_____/   \/_____/   \/_____/
 *   ______     ______       __     ______     ______     ______
 *  /\  __ \   /\  == \     /\ \   /\  ___\   /\  ___\   /\__  _\
 *  \ \ \/\ \  \ \  __<    _\_\ \  \ \  __\   \ \ \____  \/_/\ \/
 *   \ \_____\  \ \_____\ /\_____\  \ \_____\  \ \_____\    \ \_\
 *    \/_____/   \/_____/ \/_____/   \/_____/   \/_____/     \/_/
 *
 * https://joshbassett.info
 * https://twitter.com/nullobject
 * https://github.com/nullobject
 *
 * Copyright (c) 2022 Josh Bassett
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cave

import axon._
import axon.gfx._
import axon.mem._
import axon.mister._
import axon.snd._
import axon.types._
import cave.types._
import chisel3._
import chisel3.stage._
import chisel3.util.RegEnable

/**
 * The top-level module.
 *
 * The main module abstracts the rest of the arcade hardware from MiSTer-specific things (e.g.
 * memory arbiter, frame buffer) that are not part of the original arcade hardware design.
 */
class Main extends Module {
  val io = IO(new Bundle {
    /** Video clock domain */
    val videoClock = Input(Clock())
    /** Video reset */
    val videoReset = Input(Bool())
    /** CPU clock domain */
    val cpuClock = Input(Clock())
    /** CPU reset */
    val cpuReset = Input(Bool())
    /** Video port */
    val video = VideoIO()
    /** RGB output */
    val rgb = Output(RGB(Config.DDR_FRAME_BUFFER_BITS_PER_CHANNEL.W))
    /** Joystick port */
    val joystick = JoystickIO()
    /** IOCTL port */
    val ioctl = IOCTL()
    /** Audio port */
    val audio = Output(new Audio(Config.ymzConfig.sampleWidth))
    /** DDR port */
    val ddr = DDRIO(Config.ddrConfig)
    /** SDRAM port */
    val sdram = SDRAMIO(Config.sdramConfig)
    /** Options port */
    val options = OptionsIO()
    /** LED port */
    val led = mister.LEDIO()
  })

  // The download done register is latched when the ROM download has completed.
  val downloadDoneReg = Util.latchSync(Util.falling(io.ioctl.download))

  // The game configuration register is latched when data is written to the download port (i.e. the
  // game index is set by the MRA file).
  val gameConfigReg = {
    val gameConfig = Reg(GameConfig())
    val latched = RegInit(false.B)
    when(io.ioctl.download && io.ioctl.wr && io.ioctl.index === IOCTL.GAME_INDEX.U) {
      gameConfig := GameConfig(io.ioctl.dout(OptionsIO.GAME_INDEX_WIDTH - 1, 0))
      latched := true.B
    }
    // Default to the game configuration set in the options
    when(Util.falling(io.ioctl.download) && !latched) {
      gameConfig := GameConfig(io.options.gameIndex)
      latched := true.B
    }
    gameConfig
  }

  // DDR controller
  val ddr = Module(new DDR(Config.ddrConfig))
  ddr.io.ddr <> io.ddr

  // SDRAM controller
  val sdram = Module(new SDRAM(Config.sdramConfig))
  sdram.io.sdram <> io.sdram

  // Memory subsystem
  val memSys = Module(new MemSys)
  memSys.io.gameConfig <> gameConfigReg
  memSys.io.ioctl <> io.ioctl
  memSys.io.ddr <> ddr.io.mem
  memSys.io.sdram <> sdram.io.mem

  val video = Wire(VideoIO())

  withClockAndReset(io.videoClock, io.videoReset) {
    val videoTiming = Module(new VideoTiming(Config.originalVideoTimingConfig))
    videoTiming.io.offset := RegEnable(io.options.offset, videoTiming.io.video.vSync)
    videoTiming.io.video <> video
  }

  video <> io.video

  // Cave
  val cave = Module(new Cave)
  cave.io.videoClock := io.videoClock
  cave.io.videoReset := io.videoReset
  cave.io.cpuClock := io.cpuClock
  cave.io.cpuReset := io.cpuReset
  cave.io.gameConfig <> gameConfigReg
  cave.io.options <> io.options
  cave.io.joystick <> io.joystick
  cave.io.progRom <> DataFreezer.freeze(io.cpuClock, memSys.io.progRom)
  cave.io.soundRom <> DataFreezer.freeze(io.cpuClock, memSys.io.soundRom)
  cave.io.eeprom <> DataFreezer.freeze(io.cpuClock, memSys.io.eeprom)
  cave.io.layer0Rom <> ClockDomain.syncronize(io.videoClock, memSys.io.layer0Rom)
  cave.io.layer1Rom <> ClockDomain.syncronize(io.videoClock, memSys.io.layer1Rom)
  cave.io.layer2Rom <> ClockDomain.syncronize(io.videoClock, memSys.io.layer2Rom)
  cave.io.spriteRom <> memSys.io.spriteRom
  cave.io.audio <> io.audio
  cave.io.video <> video
  cave.io.rgb <> io.rgb

  // Set LED outputs
  io.led.power := false.B
  io.led.disk := io.ioctl.waitReq
  io.led.user := io.ioctl.download
}

object Main extends App {
  (new ChiselStage).execute(
    Array("--compiler", "verilog", "--target-dir", "quartus/rtl", "--output-file", "ChiselTop"),
    Seq(ChiselGeneratorAnnotation(() => new Main))
  )
}
