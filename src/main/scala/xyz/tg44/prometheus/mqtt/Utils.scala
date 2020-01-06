package xyz.tg44.prometheus.mqtt

import spray.json._
import xyz.tg44.prometheus.Config.PatternConf
import xyz.tg44.prometheus.exporter.Gauge
import xyz.tg44.prometheus.exporter.Registry.MetricMeta

import scala.annotation.tailrec
import scala.util.Try

object Utils {
  def flatten(topic: String, payload: String): List[(String, Double)] = {
    Try(payload.parseJson).toOption match {
      case Some(JsNumber(n)) => (topic -> n.doubleValue) :: Nil
      case Some(o: JsObject) => flatten(o, topic)
      case _ => Nil
    }
  }

  def flatten(o: JsObject, acc: String): List[(String, Double)] = {
    o.fields.toList.flatMap{case (k, v) =>
      v match {
        case JsNumber(n) => (s"$acc/$k" -> n.doubleValue) :: Nil
        case o: JsObject => flatten(o, s"$acc/$k")
        case _ => Nil
      }
    }
  }

  def topicFromPattern(pattern: String): String = {
    val splitted = pattern.split('/')
    if(splitted.contains("|")) {
      splitted
        .takeWhile(_ != "|")
        .map( p =>
          if(p.startsWith("<<") && p.endsWith(">>")) {
            "+"
          } else if(p == "[[prefix]]"){
            "+"
          } else {
            p
          }
        )
        .mkString("/")
    } else {
      if(splitted.length > 1) {
        splitted.head + "/#"
      } else {
        splitted.head
      }
    }
  }

  def metaFromPatternAndPath(pattern: String, path: String, prefix: String): Option[MetricMeta] = {
    val patternList = pattern.split('/').filter(_ != "|").toList
    val pathList = path.split('/').toList

    @tailrec
    def rec(patterns: List[String], paths: List[String], metrics: List[String], labels: Map[String, String]): Option[MetricMeta] = {
      patterns match {
        case h :: Nil if paths.size > 1 =>
          if(h == "[[prefixes]]") {
            metaForPatterns(metrics.reverse ++ paths, labels)
          } else {
            println("a")
            None
          }
        case h :: t =>
          if(h == "[[prefix]]") {
            rec(t, paths.tail, paths.head :: metrics, labels)
          } else if(h.startsWith("<<") && h.endsWith(">>")) {
            val label = h.drop(2).dropRight(2)
            rec(t, paths.tail, metrics, labels + (label -> paths.head))
          } else if(h == paths.head){
            rec(t, paths.tail, metrics, labels)
          } else {
            None
          }
        case Nil if paths.isEmpty =>
          metaForPatterns(metrics.reverse, labels)
        case Nil =>
          None
      }
    }

    if(patternList.size > pathList.size) {
      None
    } else {
      rec(patternList, pathList, prefix :: Nil, Map.empty)
    }
  }

  def metaBuilder(prefixPathList: Seq[PatternConf])(path: String): Option[MetricMeta] = {
    prefixPathList.map(p => metaFromPatternAndPath(p.pattern, _, p.prefix)).foldLeft(Option.empty[MetricMeta])(_ orElse _(path))
  }

  def metaForPatterns(metrics: List[String], labels: Map[String, String]): Option[MetricMeta] = {
    def standardize(s: String): String = {
      s.replace(' ', '_').replace("[^A-Za-z0-9_]", "").trim.toLowerCase
    }
    val metricName =
      metrics
        .map(standardize)
        .filter(_.nonEmpty)
        .mkString("_")
    if(metricName.isEmpty) {
      None
    } else {
      val sL = labels.map{case (k, v) => standardize(k) -> standardize(v)}
      Option(MetricMeta(Gauge.mType, metricName, sL, "", None))
    }
  }

}
