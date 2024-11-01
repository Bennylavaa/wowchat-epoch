package wowchat.game

import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.{Executors, TimeUnit}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer

import wowchat.common._
import io.netty.buffer.{ByteBuf, PooledByteBufAllocator}
import io.netty.channel.{ChannelFuture, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import wowchat.commands.{CommandHandler, WhoResponse}

import scala.io.Source
import scala.util.control.Breaks.{breakable, break}
import scala.util.Random

class GamePacketHandlerWotLK(realmId: Int, realmName: String, sessionKey: Array[Byte], gameEventCallback: CommonConnectionCallback)
  extends GamePacketHandlerTBC(realmId, realmName, sessionKey, gameEventCallback) with GamePacketsWotLK {

  override protected val addonInfo: Array[Byte] = Array(
    0x9E, 0x02, 0x00, 0x00, 0x78, 0x9C, 0x75, 0xD2, 0xC1, 0x6A, 0xC3, 0x30, 0x0C, 0xC6, 0x71, 0xEF,
    0x29, 0x76, 0xE9, 0x9B, 0xEC, 0xB4, 0xB4, 0x50, 0xC2, 0xEA, 0xCB, 0xE2, 0x9E, 0x8B, 0x62, 0x7F,
    0x4B, 0x44, 0x6C, 0x39, 0x38, 0x4E, 0xB7, 0xF6, 0x3D, 0xFA, 0xBE, 0x65, 0xB7, 0x0D, 0x94, 0xF3,
    0x4F, 0x48, 0xF0, 0x47, 0xAF, 0xC6, 0x98, 0x26, 0xF2, 0xFD, 0x4E, 0x25, 0x5C, 0xDE, 0xFD, 0xC8,
    0xB8, 0x22, 0x41, 0xEA, 0xB9, 0x35, 0x2F, 0xE9, 0x7B, 0x77, 0x32, 0xFF, 0xBC, 0x40, 0x48, 0x97,
    0xD5, 0x57, 0xCE, 0xA2, 0x5A, 0x43, 0xA5, 0x47, 0x59, 0xC6, 0x3C, 0x6F, 0x70, 0xAD, 0x11, 0x5F,
    0x8C, 0x18, 0x2C, 0x0B, 0x27, 0x9A, 0xB5, 0x21, 0x96, 0xC0, 0x32, 0xA8, 0x0B, 0xF6, 0x14, 0x21,
    0x81, 0x8A, 0x46, 0x39, 0xF5, 0x54, 0x4F, 0x79, 0xD8, 0x34, 0x87, 0x9F, 0xAA, 0xE0, 0x01, 0xFD,
    0x3A, 0xB8, 0x9C, 0xE3, 0xA2, 0xE0, 0xD1, 0xEE, 0x47, 0xD2, 0x0B, 0x1D, 0x6D, 0xB7, 0x96, 0x2B,
    0x6E, 0x3A, 0xC6, 0xDB, 0x3C, 0xEA, 0xB2, 0x72, 0x0C, 0x0D, 0xC9, 0xA4, 0x6A, 0x2B, 0xCB, 0x0C,
    0xAF, 0x1F, 0x6C, 0x2B, 0x52, 0x97, 0xFD, 0x84, 0xBA, 0x95, 0xC7, 0x92, 0x2F, 0x59, 0x95, 0x4F,
    0xE2, 0xA0, 0x82, 0xFB, 0x2D, 0xAA, 0xDF, 0x73, 0x9C, 0x60, 0x49, 0x68, 0x80, 0xD6, 0xDB, 0xE5,
    0x09, 0xFA, 0x13, 0xB8, 0x42, 0x01, 0xDD, 0xC4, 0x31, 0x6E, 0x31, 0x0B, 0xCA, 0x5F, 0x7B, 0x7B,
    0x1C, 0x3E, 0x9E, 0xE1, 0x93, 0xC8, 0x8D
  ).map(_.toByte)

  override protected def parseAuthChallenge(msg: Packet): AuthChallengeMessage = {
    val account = Global.config.wow.account

    msg.byteBuf.skipBytes(4) // wotlk
    val serverSeed = msg.byteBuf.readInt
    val clientSeed = Random.nextInt
    val out = PooledByteBufAllocator.DEFAULT.buffer(200, 400)
    out.writeShortLE(0)
    out.writeIntLE(WowChatConfig.getGameBuild)
    out.writeIntLE(0)
    out.writeBytes(account)
    out.writeByte(0)
    out.writeInt(0) // wotlk
    out.writeInt(clientSeed)
    out.writeIntLE(0) // wotlk
    out.writeIntLE(0) // wotlk
    out.writeIntLE(realmId) // wotlk
    out.writeLongLE(3) // wotlk

    val md = MessageDigest.getInstance("SHA1")
    md.update(account)
    md.update(Array[Byte](0, 0, 0, 0))
    md.update(ByteUtils.intToBytes(clientSeed))
    md.update(ByteUtils.intToBytes(serverSeed))
    md.update(sessionKey)
    out.writeBytes(md.digest)

    out.writeBytes(addonInfo)

    AuthChallengeMessage(sessionKey, out)
  }

  override protected def parseNameQuery(msg: Packet): NameQueryMessage = {
    val guid = unpackGuid(msg.byteBuf)

    val nameKnown = msg.byteBuf.readByte // wotlk
    val (name, charClass) = if (nameKnown == 0) {
      val name = msg.readString
      msg.skipString // realm name for cross bg usage

      // wotlk changed the char info to bytes
      msg.byteBuf.skipBytes(1) // race
      msg.byteBuf.skipBytes(1) // gender
      val charClass = msg.byteBuf.readByte
      (name, charClass)
    } else {
      logger.error(s"RECV SMSG_NAME_QUERY - Name not known for guid $guid")
      ("UNKNOWN", 0xFF.toByte)
    }

    NameQueryMessage(guid, name, charClass)
  }

  override protected def parseCharEnum(msg: Packet): Option[CharEnumMessage] = {
    val characterBytes = Global.config.wow.character.toLowerCase.getBytes("UTF-8")
    val charactersNum = msg.byteBuf.readByte

    // only care about guid and name here
    (0 until charactersNum).foreach(i => {
      val guid = msg.byteBuf.readLongLE
      val name = msg.readString
      val race = msg.byteBuf.readByte // will determine what language to use in chat

      msg.byteBuf.skipBytes(1) // class
      msg.byteBuf.skipBytes(1) // gender
      msg.byteBuf.skipBytes(1) // skin
      msg.byteBuf.skipBytes(1) // face
      msg.byteBuf.skipBytes(1) // hair style
      msg.byteBuf.skipBytes(1) // hair color
      msg.byteBuf.skipBytes(1) // facial hair
      msg.byteBuf.skipBytes(1) // level
      msg.byteBuf.skipBytes(4) // zone
      msg.byteBuf.skipBytes(4) // map - could be useful in the future to determine what city specific channels to join

      msg.byteBuf.skipBytes(12) // x + y + z

      val guildGuid = msg.byteBuf.readIntLE
      if (name.toLowerCase.getBytes("UTF-8").sameElements(characterBytes)) {
        return Some(CharEnumMessage(name, guid, race, guildGuid))
      }

      msg.byteBuf.skipBytes(4) // character flags
      msg.byteBuf.skipBytes(4) // character customize flags WotLK only
      msg.byteBuf.skipBytes(1) // first login
      msg.byteBuf.skipBytes(12) // pet info
      msg.byteBuf.skipBytes(19 * 9) // equipment info TBC has 9 slot equipment info
      msg.byteBuf.skipBytes(4 * 9) // bag display for WotLK has all 4 bags
    })
    None
  }

  override protected def sendGroupInvite(name: String): Unit = {
    ctx.get.writeAndFlush(buildSingleStringPacketWRATH(CMSG_GROUP_INVITE, name.toLowerCase()))
  }
  
protected def buildSingleStringPacketWRATH(
    opcode: Int,
    string_param: String
): Packet = {
    val stringBytes = string_param.getBytes("UTF-8")
    val totalLength = stringBytes.length + 1 + 4 // Length of string + null terminator + UInt32

    val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(totalLength) // Adjusted size dynamically
    byteBuf.writeBytes(stringBytes)    // Write the string
    byteBuf.writeByte(0)                // Null terminator for the string
    byteBuf.writeInt(0)                 // Additional UInt32 (0x0) as required by WotLK
    Packet(opcode, byteBuf)
}

override protected def handle_SMSG_GROUP_LIST(msg: Packet): Unit = {
    logger.debug(s"DEBUG: ${ByteUtils.toHexString(msg.byteBuf, true, true)}")

    val groupType = msg.byteBuf.readByte() // u8 group_type
    val groupId = msg.byteBuf.readByte()   // u8 group_id
    val flags = msg.byteBuf.readByte()     // u8 flags
    val roles = msg.byteBuf.readByte()     // u8 roles
    val groupGuid = msg.byteBuf.readLongLE() // Guid group (64-bit)
    val counter = msg.byteBuf.readIntLE()  // u32 counter
    val memberCount = msg.byteBuf.readIntLE() // u32 amount_of_members

    for (i <- 1 to memberCount) {
      val name = readString(msg.byteBuf)
      msg.byteBuf.skipBytes(8) // guid
      val isOnline = msg.byteBuf.readBoolean()
      msg.byteBuf.skipBytes(1) // flags

      val cachedOnlineState = groupMembers.get(name)
      if (Some(isOnline) != cachedOnlineState) {
        cachedOnlineState match {
          case Some(true) => {
            logger.debug(
              s"Person went offline! doing the thing ($name -> $isOnline)"
            )
            groupMembers(name) = isOnline
            sendResetInstances()
            // sendGroupKick(name)
          }
          case _ => {
            groupMembers(name) = isOnline
          }
        }
      }
      logger.debug(s"Member #$i: $name - is online: $isOnline")
    }

    val leaderGuid = msg.byteBuf.readLongLE() // Guid leader

    if (flags != 0) { // Optional block: group_not_empty
        val lootSetting = msg.byteBuf.readByte() // GroupLootSetting (u8)
        val masterLootGuid = msg.byteBuf.readLongLE() // Guid master_loot
        val lootThreshold = msg.byteBuf.readByte() // ItemQuality (u8)
        val dungeonDifficulty = msg.byteBuf.readByte() // DungeonDifficulty (u8)
        val raidDifficulty = msg.byteBuf.readByte() // RaidDifficulty (u8)
        val heroic = msg.byteBuf.readBoolean() // Bool heroic
    }
}


override protected def parseChatMessage(msg: Packet): Option[ChatMessage] = {
  // Read the type of chat message
  val tp = msg.byteBuf.readByte
  logger.debug(s"DEBUG: tp value is $tp")  // Log the message type

  // Read the language identifier
  val lang = msg.byteBuf.readIntLE
  // Ignore addon messages
  if (lang == -1) {
    logger.debug("DEBUG: Skipping addon message due to lang == -1")
    return None
  }

  // Read the GUID of the message sender
  val guid = msg.byteBuf.readLongLE
  // Ignore messages from itself, unless it is a system message
  if (tp != ChatEvents.CHAT_MSG_SYSTEM && guid == selfCharacterId.get) {
    logger.debug(s"DEBUG: Skipping message from self, guid: $guid")
    return None
  }

  // Skip unused bytes
  msg.byteBuf.skipBytes(4)

  // Check for GM messages and skip if present
  if (msg.id == SMSG_GM_MESSAGECHAT) {
    msg.byteBuf.skipBytes(4)
    msg.skipString
  }

  // Read channel name if applicable
  val channelName = if (tp == ChatEvents.CHAT_MSG_CHANNEL) {
    Some(msg.readString)
  } else {
    None
  }

  // Skip GUID again
  msg.byteBuf.skipBytes(8) // skip guid again
  logger.debug(s"DEBUG: Buffer readable bytes after guid skip: ${msg.byteBuf.readableBytes()}")

  // Read text length
  val txtLen = msg.byteBuf.readIntLE
  logger.debug(s"DEBUG: txtLen value is $txtLen")

  // Read the text message from the buffer
  val txt = msg.byteBuf.readCharSequence(txtLen - 1, Charset.forName("UTF-8")).toString
  logger.debug(s"DEBUG: Parsed txt value is '$txt'")

  // Skip the null terminator and chat tag
  msg.byteBuf.skipBytes(1) // null terminator
  msg.byteBuf.skipBytes(1) // chat tag

  // Check for whispers containing 'camp' or 'invite'
  if (tp == ChatEvents.CHAT_MSG_WHISPER && (txt.toLowerCase.contains("camp") || txt.toLowerCase.contains("invite"))) {
    playersToGroupInvite += guid
    logger.debug(s"PLAYER INVITATION: added $guid to the queue")
  }

  // Skip unhandled channel messages unless it's a guild achievement message
  if (tp != ChatEvents.CHAT_MSG_GUILD_ACHIEVEMENT && !Global.wowToDiscord.contains((tp, channelName.map(_.toLowerCase)))) {
    logger.debug(s"DEBUG: Skipping unhandled channel message of type $tp")
    return None
  }

  // Handle guild achievement messages separately
  if (tp == ChatEvents.CHAT_MSG_GUILD_ACHIEVEMENT) {
    handleAchievementEvent(guid, msg.byteBuf.readIntLE)
    None
  } else {
    // Return the parsed chat message
    Some(ChatMessage(guid, tp, txt, channelName))
  }
}

  protected def handleAchievementEvent(guid: Long, achievementId: Int): Unit = {
    // This is a guild event so guid MUST be in roster already
    // (unless some weird edge case -> achievement came before roster update)
    guildRoster.get(guid).foreach(player => {
      Global.discord.sendAchievementNotification(player.name, achievementId)
    })
  }

  // saving those single 0 bytes like whoa
  private def unpackGuid(byteBuf: ByteBuf): Long = {
    val set = byteBuf.readByte

    (0 until 8).foldLeft(0L) {
      case (result, i) =>
        val onBit = 1 << i
        if ((set & onBit) == onBit) {
          result | ((byteBuf.readByte & 0xFFL) << (i * 8))
        } else {
          result
        }
    }
  }
}