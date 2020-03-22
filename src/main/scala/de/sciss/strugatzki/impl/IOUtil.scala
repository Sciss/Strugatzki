/*
 *  IOUtil.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki
package impl

import de.sciss.file._
import de.sciss.synth.io.{AudioFile, AudioFileSpec, AudioFileType, SampleFormat}

object IOUtil {
  def createTempAudioFile(id: String, numChannels: Int, sampleRate: Double = 44100): AudioFile = {
    val file  = File.createTemp(s"struga_$id", suffix = ".irc")
    val spec  = AudioFileSpec(fileType = AudioFileType.IRCAM, sampleFormat = SampleFormat.Float,
      numChannels = numChannels, sampleRate = sampleRate)
    AudioFile.openWrite(file, spec)
  }
}