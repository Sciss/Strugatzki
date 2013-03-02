package de.sciss.strugatzki.impl

import de.sciss.span.Span
import xml.NodeSeq

object SpanUtil {
  def fromXML(xml: NodeSeq): Span.NonVoid = {
    val start = (xml \ "start").headOption.map(_.text.toLong)
    val stop  = (xml \ "stop").headOption.map(_.text.toLong)
    (start, stop) match {
      case (Some(_start), Some(_stop)) => Span(_start, _stop)
      case (Some(_start), None)        => Span.from(_start)
      case (None,         Some(_stop)) => Span.until(_stop)
      case (None,         None)        => Span.all
    }
  }

  def toXML(span: Span.NonVoid): xml.NodeSeq = {
    val startSeq  = span match { case Span.HasStart(s) => <start>{s}</start>; case _ => Nil}
    val stopSeq   = span match { case Span.HasStop(s) => <stop>{s}</stop>; case _ => Nil}
    if (startSeq.isEmpty && stopSeq.isEmpty) Nil else startSeq ++ stopSeq
  }

  def spacing(a: Span, b: Span): Long = {
    if (a.start < b.start) {
      b.start - a.stop
    } else {
      a.start - b.stop
    }
  }
}