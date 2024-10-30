package wowchat.game

import wowchat.common.{WowChatConfig, WowExpansion}
import scala.io.Source
import scala.util.matching.Regex

object GameResources {

  lazy val AREA: Map[Int, String] = readIDNameFile(WowChatConfig.getExpansion match {
    case WowExpansion.Vanilla | WowExpansion.TBC | WowExpansion.WotLK => "pre_cata_areas.csv"
    case _ => "post_cata_areas.csv"
  })

  lazy val ACHIEVEMENT: Map[Int, String] = readIDNameFile("achievements.csv")

  private def readIDNameFile(file: String): Map[Int, String] = {
    // Regex to match ID and name considering both quoted and unquoted names
    val achievementPattern: Regex = """(\d+),\s*(?:"([^"]*)"|([^",]*))""".r

    Source
      .fromResource(file)
      .getLines
      .flatMap {
        case achievementPattern(idStr, quotedName, unquotedName) =>
          val name = Option(quotedName).orElse(Option(unquotedName)).getOrElse("")
          try {
            Some(idStr.toInt -> name.trim)
          } catch {
            case _: NumberFormatException =>
              println(s"Invalid ID format: $idStr")
              None
          }
        case line =>
          println(s"Invalid line format: $line")
          None
      }
      .toMap
  }
}
