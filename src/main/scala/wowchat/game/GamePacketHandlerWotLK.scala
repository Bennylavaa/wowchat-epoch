package wowchat.game

import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.{Executors, TimeUnit}

import wowchat.common._
import wowchat.game.warden.{WardenHandler, WardenPackets}
import com.typesafe.scalalogging.StrictLogging
import io.netty.buffer.{ByteBuf, PooledByteBufAllocator}
import io.netty.channel.{
  ChannelFuture,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter
}
import wowchat.commands.{CommandHandler, WhoResponse}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.control.Breaks.{breakable, break}
import scala.util.Random

class GamePacketHandlerWotLK(
    realmId: Int,
    realmName: String,
    sessionKey: Array[Byte],
    gameEventCallback: CommonConnectionCallback
) extends GamePacketHandlerTBC(
    realmId,
    realmName,
    sessionKey,
    gameEventCallback
)
    with GameCommandHandler
    with GamePacketsWotLK
    with StrictLogging {

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

  protected var selfCharacterId: Option[Long] = None
  protected var languageId: Byte = _
  protected var inWorld: Boolean = false
  protected var guildGuid: Long = _
  protected var guildInfo: GuildInfo = _
  protected var guildMotd: Option[String] = None

  protected var ctx: Option[ChannelHandlerContext] = None
  protected val playerRoster = LRUMap.empty[Long, Player]
  protected val playerRosterCached = LRUMap.empty[Long, String]
  protected val playersToGroupInvite: HashSet[Long] = HashSet[Long]()
  protected val groupMembers = mutable.Map.empty[String, Boolean]
  protected val guildRoster = mutable.Map.empty[Long, GuildMember]
  protected var lastRequestedGuildRoster: Long = _
  protected val executorService = Executors.newSingleThreadScheduledExecutor

  // cannot use multimap here because need deterministic order
  private val queuedChatMessages =
    new mutable.HashMap[Long, mutable.ListBuffer[ChatMessage]]
  private var wardenHandler: Option[WardenHandler] = None
  private var receivedCharEnum = false

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    executorService.shutdown()
    this.ctx = None
    gameEventCallback.disconnected
    Global.game = None
    if (inWorld) {
      Global.discord.sendMessageFromWow(
        None,
        "Disconnected from server!",
        ChatEvents.CHAT_MSG_SYSTEM,
        None
      )
    }
    super.channelInactive(ctx)
  }

  private def runGroupInviteExecutor: Unit = {
    executorService.scheduleWithFixedDelay(
      () => {
        val guidsToRemove: HashSet[Long] = HashSet[Long]()

        playersToGroupInvite.foreach { guid =>
          logger.debug(s"Player group invitation: handling ${guid}...")
          var player_name = playerRosterCached.get(guid)
          player_name match {
            case Some(name) =>
              logger.debug(s"Inviting player '${name}'")
              groupConvertToRaid
              sendGroupInvite(name)
              guidsToRemove += guid
            case None =>
              logger.debug(
                s"Player invitation:'$guid' not cached, sending name query..."
              )
              sendNameQuery(guid)
          }
        }

        guidsToRemove.foreach(playersToGroupInvite.remove)

      },
      3,
      3,
      TimeUnit.SECONDS
    )
  }

  // Vanilla does not have a keep alive packet
  override protected def runKeepAliveExecutor: Unit = {}

  private def runPingExecutor: Unit = {
    executorService.scheduleWithFixedDelay(
      new Runnable {
        var pingId = 0

        override def run(): Unit = {
          val latency = Random.nextInt(50) + 90

          val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(8, 8)
          byteBuf.writeIntLE(pingId)
          byteBuf.writeIntLE(latency)

          ctx.get.writeAndFlush(Packet(CMSG_PING, byteBuf))
          pingId += 1
        }
      },
      30,
      30,
      TimeUnit.SECONDS
    )
  }

  private def runGuildRosterExecutor: Unit = {
    executorService.scheduleWithFixedDelay(
      () => {
        // Enforce updating guild roster only once per minute
        if (System.currentTimeMillis - lastRequestedGuildRoster >= 60000) {
          updateGuildRoster
        }
      },
      61,
      61,
      TimeUnit.SECONDS
    )
  }

  def buildGuildiesOnline: String = {
    val characterName = Global.config.wow.character

    guildRoster.valuesIterator
      .filter(guildMember =>
        guildMember.isOnline && !guildMember.name.equalsIgnoreCase(
          characterName
        )
      )
      .toSeq
      .sortBy(_.name)
      .map(m => {
        s"${m.name} (${m.level} ${Classes.valueOf(m.charClass)} in ${GameResources.AREA
            .getOrElse(m.zoneId, "Unknown Zone")})"
      })
      .mkString(getGuildiesOnlineMessage(false), ", ", "")
  }

  def getGuildiesOnlineMessage(isStatus: Boolean): String = {
    val size = guildRoster.count(_._2.isOnline) - 1
    val guildies = s"guildie${if (size != 1) "s" else ""}"

    if (isStatus) {
      s"$size $guildies online"
    } else {
      if (size <= 0) {
        "Currently no guildies online."
      } else {
        s"Currently $size $guildies online:\n"
      }
    }
  }

  override protected def updateGuildiesOnline: Unit = {
    Global.discord.changeGuildStatus(getGuildiesOnlineMessage(true))
  }

  override protected def queryGuildName: Unit = {
    val out = PooledByteBufAllocator.DEFAULT.buffer(4, 4)
    out.writeIntLE(guildGuid.toInt)
    ctx.get.writeAndFlush(Packet(CMSG_GUILD_QUERY, out))
  }

  private def updateGuildRoster: Unit = {
    lastRequestedGuildRoster = System.currentTimeMillis
    ctx.get.writeAndFlush(buildGuildRosterPacket)
  }

  override protected def buildGuildRosterPacket: Packet = {
    Packet(CMSG_GUILD_ROSTER)
  }

  def sendLogout: Option[ChannelFuture] = {
    ctx.flatMap(ctx => {
      if (ctx.channel.isActive) {
        Some(ctx.writeAndFlush(Packet(CMSG_LOGOUT_REQUEST)))
      } else {
        None
      }
    })
  }

  override def sendMessageToWow(
      tp: Byte,
      message: String,
      target: Option[String]
  ): Unit = {
    ctx.fold(logger.error("Cannot send message! Not connected to WoW!"))(
      ctx => {
        ctx.writeAndFlush(
          buildChatMessage(
            tp,
            message.getBytes("UTF-8"),
            target.map(_.getBytes("UTF-8"))
          )
        )
      }
    )
  }

  override protected def buildChatMessage(
      tp: Byte,
      utf8MessageBytes: Array[Byte],
      utf8TargetBytes: Option[Array[Byte]]
  ): Packet = {
    val out = PooledByteBufAllocator.DEFAULT.buffer(128, 8192)
    out.writeIntLE(tp)
    out.writeIntLE(languageId)
    utf8TargetBytes.foreach(utf8TargetBytes => {
      out.writeBytes(utf8TargetBytes)
      out.writeByte(0)
    })
    out.writeBytes(utf8MessageBytes)
    out.writeByte(0)
    Packet(CMSG_MESSAGECHAT, out)
  }

  override def sendNotification(message: String): Unit = {
    sendMessageToWow(ChatEvents.CHAT_MSG_GUILD, message, None)
  }

  override protected def sendGroupInvite(name: String): Unit = {
    ctx.get.writeAndFlush(
      buildSingleStringPacket(CMSG_GROUP_INVITE, name.toLowerCase())
    )
  }

  def sendGroupKick(name: String): Unit = {
    ctx.get.writeAndFlush(
      buildSingleStringPacket(CMSG_GROUP_KICK, name.toLowerCase())
    )
  }

  def sendGroupKickUUID(name_uuid: String): Unit = {
    ctx.get.writeAndFlush(
      buildSingleStringPacket(CMSG_GROUP_KICK_UUID, name_uuid)
    )
  }

  def sendGuildInvite(name: String): Unit = {
    ctx.get.writeAndFlush(
      buildSingleStringPacket(CMSG_GUILD_INVITE, name.toLowerCase())
    )
  }

  def sendGuildKick(name: String): Unit = {
    ctx.get.writeAndFlush(
      buildSingleStringPacket(CMSG_GUILD_REMOVE, name.toLowerCase())
    )
  }

  def sendResetInstances(): Unit = {
    ctx.get.writeAndFlush(Packet(CMSG_RESET_INSTANCES))
  }

  override protected def buildSingleStringPacket(
      opcode: Int,
      string_param: String
  ): Packet = {
    val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(8, 16)
    byteBuf.writeBytes(string_param.getBytes("UTF-8"))
    byteBuf.writeByte(0)
    Packet(opcode, byteBuf)
  }

  def groupDisband(): Unit = {
    logger.debug(s"Disbanding group...")
    ctx.get.writeAndFlush(Packet(CMSG_GROUP_DISBAND))
  }

  def groupConvertToRaid(): Unit = {
    ctx.get.writeAndFlush(Packet(CMSG_GROUP_RAID_CONVERT))
  }

  def updateGroupLootMethod(mode: Int, masterGUID: Long = 0, threshold: Int = 2) = {
    val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(16, 16)
    byteBuf.writeIntLE(mode)
    byteBuf.writeLongLE(masterGUID)
    byteBuf.writeIntLE(threshold)
    ctx.get.writeAndFlush(Packet(CMSG_LOOT_METHOD, byteBuf))
  }

  def sendNameQuery(guid: Long): Unit = {
    ctx.foreach(ctx => {
      val out = PooledByteBufAllocator.DEFAULT.buffer(8, 8)
      out.writeLongLE(guid)
      ctx.writeAndFlush(Packet(CMSG_NAME_QUERY, out))
    })
  }

  override def handleWho(arguments: Option[String]): Option[String] = {
    if (arguments.isDefined) {
      val byteBuf = buildWhoMessage(arguments.get)
      ctx.get.writeAndFlush(Packet(CMSG_WHO, byteBuf))
      None
    } else {
      Some(buildGuildiesOnline)
    }
  }

  override def handleGmotd(): Option[String] = {
    guildMotd.map(guildMotd => {
      val guildNotificationConfig =
        Global.config.guildConfig.notificationConfigs("motd")
      guildNotificationConfig.format
        .replace("%time", Global.getTime)
        .replace("%user", "")
        .replace("%message", guildMotd)
    })
  }

  override protected def buildWhoMessage(name: String): ByteBuf = {
    val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(64, 64)
    byteBuf.writeIntLE(0) // level min
    byteBuf.writeIntLE(100) // level max
    byteBuf.writeBytes(name.getBytes("UTF-8"))
    byteBuf.writeByte(0) // ?
    byteBuf.writeByte(0) // ?
    byteBuf.writeIntLE(0xffffffff) // race mask (all races)
    byteBuf.writeIntLE(0xffffffff) // class mask (all classes)
    byteBuf.writeIntLE(0) // zones count
    byteBuf.writeIntLE(0) // strings count
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    logger.info("Connected! Authenticating...")
    this.ctx = Some(ctx)
    Global.game = Some(this)
    runPingExecutor
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    msg match {
      case msg: Packet =>
        channelParse(msg)
        msg.byteBuf.release
      case msg => logger.error(s"Packet is instance of ${msg.getClass}")
    }
  }

  override protected def channelParse(msg: Packet): Unit = {
    msg.id match {
      case SMSG_AUTH_CHALLENGE       => handle_SMSG_AUTH_CHALLENGE(msg)
      case SMSG_AUTH_RESPONSE        => handle_SMSG_AUTH_RESPONSE(msg)
      case SMSG_NAME_QUERY           => handle_SMSG_NAME_QUERY(msg)
      case SMSG_CHAR_ENUM            => handle_SMSG_CHAR_ENUM(msg)
      case SMSG_LOGIN_VERIFY_WORLD   => handle_SMSG_LOGIN_VERIFY_WORLD(msg)
      case SMSG_GUILD_QUERY          => handle_SMSG_GUILD_QUERY(msg)
      case SMSG_GUILD_EVENT          => handle_SMSG_GUILD_EVENT(msg)
      case SMSG_GUILD_ROSTER         => handle_SMSG_GUILD_ROSTER(msg)
      case SMSG_MESSAGECHAT          => handle_SMSG_MESSAGECHAT(msg)
      case SMSG_CHANNEL_NOTIFY       => handle_SMSG_CHANNEL_NOTIFY(msg)
      case SMSG_NOTIFICATION         => handle_SMSG_NOTIFICATION(msg)
      case SMSG_WHO                  => handle_SMSG_WHO(msg)
      case SMSG_SERVER_MESSAGE       => handle_SMSG_SERVER_MESSAGE(msg)
      case SMSG_INVALIDATE_PLAYER    => handle_SMSG_INVALIDATE_PLAYER(msg)
      case SMSG_PARTY_COMMAND_RESULT => handle_SMSG_PARTY_COMMAND_RESULT(msg)
      case SMSG_GROUP_LIST           => handle_SMSG_GROUP_LIST(msg)

      case SMSG_WARDEN_DATA => handle_SMSG_WARDEN_DATA(msg)

      case unhandled =>
    }
  }

  private def handle_SMSG_AUTH_CHALLENGE(msg: Packet): Unit = {
    val authChallengeMessage = parseAuthChallenge(msg)

    ctx.get.channel.attr(CRYPT).get.init(authChallengeMessage.sessionKey)

    ctx.get.writeAndFlush(
      Packet(CMSG_AUTH_CHALLENGE, authChallengeMessage.byteBuf)
    )
  }

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

  private def handle_SMSG_AUTH_RESPONSE(msg: Packet): Unit = {
    val code = parseAuthResponse(msg)
    if (code == AuthResponseCodes.AUTH_OK) {
      logger.info("Successfully logged in!")
      sendCharEnum
    } else if (code == AuthResponseCodes.AUTH_WAIT_QUEUE) {
      if (msg.byteBuf.readableBytes() >= 14) {
        msg.byteBuf.skipBytes(10)
      }
      val position = msg.byteBuf.readIntLE
      logger.info(s"Queue enabled. Position: $position")
    } else {
      logger.error(AuthResponseCodes.getMessage(code))
      ctx.foreach(_.close)
      gameEventCallback.error
    }
  }

  private def sendCharEnum: Unit = {
    // Only request char enum if previous requests were unsuccessful (due to failed warden reply)
    if (!receivedCharEnum) {
      ctx.get.writeAndFlush(Packet(CMSG_CHAR_ENUM))
    }
  }

  override protected def parseAuthResponse(msg: Packet): Byte = {
    msg.byteBuf.readByte
  }

  private def handle_SMSG_NAME_QUERY(msg: Packet): Unit = {
    val nameQueryMessage = parseNameQuery(msg)
    playerRosterCached += nameQueryMessage.guid -> nameQueryMessage.name

    queuedChatMessages
      .remove(nameQueryMessage.guid)
      .foreach(messages => {
        messages.foreach(message => {
          Global.discord.sendMessageFromWow(
            Some(nameQueryMessage.name),
            message.message,
            message.tp,
            message.channel
          )
        })
        playerRoster += nameQueryMessage.guid -> Player(
          nameQueryMessage.name,
          nameQueryMessage.charClass
        )
      })
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

  private def handle_SMSG_CHAR_ENUM(msg: Packet): Unit = {
    if (receivedCharEnum) {
      if (inWorld) {
        // Do not parse char enum again if we've already joined the world.
        return
      } else {
        logger.info(
          "Received character enum more than once. Trying to join the world again..."
        )
      }
    }
    receivedCharEnum = true
    parseCharEnum(msg).fold({
      logger.error(s"Character ${Global.config.wow.character} not found!")
    })(character => {
      logger.info(s"Logging in with character ${character.name}")
      selfCharacterId = Some(character.guid)
      languageId = Races.getLanguage(character.race)
      guildGuid = character.guildGuid

      val out =
        PooledByteBufAllocator.DEFAULT.buffer(16, 16) // increase to 16 for MoP
      writePlayerLogin(out)
      ctx.get.writeAndFlush(Packet(CMSG_PLAYER_LOGIN, out))
    })
  }

  def readString(buf: ByteBuf): String = {
    val ret = ArrayBuffer.newBuilder[Byte]
    breakable {
      while (buf.readableBytes > 0) {
        val value = buf.readByte
        if (value == 0) {
          break
        }
        ret += value
      }
    }

    Source.fromBytes(ret.result.toArray, "UTF-8").mkString
  }

  override protected def handle_SMSG_GROUP_LIST(msg: Packet): Unit = {
    logger.error(s"DEBUG: ${ByteUtils.toHexString(msg.byteBuf, true, true)}")

    val isRaid = msg.byteBuf.readBoolean() // false: group, true: raid
    if (!isRaid) { groupConvertToRaid(); return }

    msg.byteBuf.skipBytes(1) // flags
    val memberCount = msg.byteBuf.readIntLE()

    for (i <- 1 to memberCount) {
      val name = readString(msg.byteBuf)
      msg.byteBuf.skipBytes(8) // guid
      val isOnline = msg.byteBuf.readBoolean()
      msg.byteBuf.skipBytes(1) // flags

      val cachedOnlineState = groupMembers.get(name)
      if (Some(isOnline) != cachedOnlineState) {
        cachedOnlineState match {
          case Some(true) => {
            logger.error(
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

    val leaderGUID = msg.byteBuf.readLongLE()
    val groupLootSetting =
      msg.byteBuf
        .readByte() // 0: FFA, 1: RR, 2: ML, 3: Group loot, 4: Need before greed
    val masterLooterGUID = msg.byteBuf.readLongLE()
    val lootQuality = msg.byteBuf.readByte() // 2: uncommon, 3: rare, 4: epic
    msg.byteBuf.skipBytes(1) // null-termination

    if (groupLootSetting != 0) {updateGroupLootMethod(0); return}
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

  override protected def writePlayerLogin(out: ByteBuf): Unit = {
    out.writeLongLE(selfCharacterId.get)
  }

  private def handle_SMSG_LOGIN_VERIFY_WORLD(msg: Packet): Unit = {
    // for some reason some servers send this packet more than once.
    if (inWorld) {
      return
    }

    logger.info("Successfully joined the world!")
    inWorld = true
    Global.discord.changeRealmStatus(realmName)
    gameEventCallback.connected
    runKeepAliveExecutor
    runGuildRosterExecutor
    runGroupInviteExecutor
    if (guildGuid != 0) {
      queryGuildName
      updateGuildRoster
    }

    // join channels
    Global.config.channels
      .flatMap(channelConfig => {
        channelConfig.wow.channel.fold[Option[(Int, String)]](None)(
          channelName => {
            Some(
              channelConfig.wow.id
                .getOrElse(ChatChannelIds.getId(channelName)) -> channelName
            )
          }
        )
      })
      .foreach { case (id, name) =>
        logger.info(s"Joining channel $name")
        val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(50, 200)
        writeJoinChannel(byteBuf, id, name.getBytes("UTF-8"))
        ctx.get.writeAndFlush(Packet(CMSG_JOIN_CHANNEL, byteBuf))
      }
  }

  override protected def writeJoinChannel(
      out: ByteBuf,
      id: Int,
      utf8ChannelBytes: Array[Byte]
  ): Unit = {
    out.writeBytes(utf8ChannelBytes)
    out.writeByte(0)
    out.writeByte(0)
  }

  private def handle_SMSG_GUILD_QUERY(msg: Packet): Unit = {
    guildInfo = handleGuildQuery(msg)
  }

  override protected def handleGuildQuery(msg: Packet): GuildInfo = {
    msg.byteBuf.skipBytes(4)
    val name = msg.readString

    val ranks = (0 until 10)
      .map(_ -> msg.readString)
      .filter { case (_, name) =>
        name.nonEmpty
      }
      .toMap

    GuildInfo(name, ranks)
  }

  private def handle_SMSG_GUILD_EVENT(msg: Packet): Unit = {
    val event = msg.byteBuf.readByte
    val numStrings = msg.byteBuf.readByte
    val messages = (0 until numStrings).map(i => msg.readString)

    handleGuildEvent(event, messages)
  }

  override protected def handleGuildEvent(event: Byte, messages: Seq[String]): Unit = {
    // ignore empty messages
    if (messages.forall(_.trim.isEmpty)) {
      return
    }

    // ignore events from self
    if (
      event != GuildEvents.GE_MOTD && Global.config.wow.character
        .equalsIgnoreCase(messages.head)
    ) {
      return
    }

    val eventConfigKey = event match {
      case GuildEvents.GE_PROMOTED   => "promoted"
      case GuildEvents.GE_DEMOTED    => "demoted"
      case GuildEvents.GE_MOTD       => "motd"
      case GuildEvents.GE_JOINED     => "joined"
      case GuildEvents.GE_LEFT       => "left"
      case GuildEvents.GE_REMOVED    => "removed"
      case GuildEvents.GE_SIGNED_ON  => "online"
      case GuildEvents.GE_SIGNED_OFF => "offline"
      case _                         => return
    }

    val guildNotificationConfig =
      Global.config.guildConfig.notificationConfigs(eventConfigKey)

    if (guildNotificationConfig.enabled) {
      val formatted = event match {
        case GuildEvents.GE_PROMOTED | GuildEvents.GE_DEMOTED =>
          guildNotificationConfig.format
            .replace("%time", Global.getTime)
            .replace("%user", messages.head)
            .replace("%message", messages.head)
            .replace("%target", messages(1))
            .replace("%rank", messages(2))
        case GuildEvents.GE_REMOVED =>
          guildNotificationConfig.format
            .replace("%time", Global.getTime)
            .replace("%user", messages(1))
            .replace("%message", messages(1))
            .replace("%target", messages.head)
        case _ =>
          guildNotificationConfig.format
            .replace("%time", Global.getTime)
            .replace("%user", messages.head)
            .replace("%message", messages.head)
      }

      Global.discord.sendGuildNotification(eventConfigKey, formatted)
    }

    updateGuildRoster
  }

  private def handle_SMSG_GUILD_ROSTER(msg: Packet): Unit = {
    guildRoster.clear
    guildRoster ++= parseGuildRoster(msg)
    updateGuildiesOnline
  }

  override protected def parseGuildRoster(msg: Packet): Map[Long, GuildMember] = {
    val count = msg.byteBuf.readIntLE
    guildMotd = Some(msg.readString)
    val ginfo = msg.readString
    val rankscount = msg.byteBuf.readIntLE
    (0 until rankscount).foreach(i => msg.byteBuf.skipBytes(4))
    (0 until count)
      .map(i => {
        val guid = msg.byteBuf.readLongLE
        val isOnline = msg.byteBuf.readBoolean
        val name = msg.readString
        msg.byteBuf.skipBytes(4) // guild rank
        val level = msg.byteBuf.readByte
        val charClass = msg.byteBuf.readByte
        val zoneId = msg.byteBuf.readIntLE
        val lastLogoff = if (!isOnline) {
          msg.byteBuf.readFloatLE
        } else {
          0
        }
        msg.skipString
        msg.skipString

        guid -> GuildMember(
          name,
          isOnline,
          charClass,
          level,
          zoneId,
          lastLogoff
        )
      })
      .toMap
  }

  override protected def handle_SMSG_MESSAGECHAT(msg: Packet): Unit = {
    logger.debug(
      s"RECV CHAT: ${ByteUtils.toHexString(msg.byteBuf, true, true)}"
    )
    parseChatMessage(msg).foreach(sendChatMessage)
  }

  override protected def handle_SMSG_PARTY_COMMAND_RESULT(msg: Packet): Unit = {
    val reply = ByteUtils.toHexString(msg.byteBuf, true, true)
    logger.debug(s"RECV PARTY COMMAND RESULT: ${reply}")

    // We get this reply when we don't have rights to invite others
    if (reply == "00 00 00 00 00 06 00 00 00") {
      groupDisband()
    }
  }

  override protected def sendChatMessage(chatMessage: ChatMessage): Unit = {
    if (chatMessage.guid == 0) {
      Global.discord.sendMessageFromWow(
        None,
        chatMessage.message,
        chatMessage.tp,
        None
      )
    } else {
      playerRoster
        .get(chatMessage.guid)
        .fold({
          queuedChatMessages
            .get(chatMessage.guid)
            .fold({
              queuedChatMessages += chatMessage.guid -> ListBuffer(chatMessage)
              sendNameQuery(chatMessage.guid)
            })(_ += chatMessage)
        })(name => {
          Global.discord.sendMessageFromWow(
            Some(name.name),
            chatMessage.message,
            chatMessage.tp,
            chatMessage.channel
          )
        })
    }
  }

  override protected def parseChatMessage(msg: Packet): Option[ChatMessage] = {
    val tp = msg.byteBuf.readByte

    val lang = msg.byteBuf.readIntLE
    // ignore addon messages
    if (lang == -1) {
      return None
    }

    // ignore messages from itself, unless it is a system message.
    val guid = msg.byteBuf.readLongLE
    if (tp != ChatEvents.CHAT_MSG_SYSTEM && guid == selfCharacterId.get) {
      return None
    }

    msg.byteBuf.skipBytes(4)

    if (msg.id == SMSG_GM_MESSAGECHAT) {
      msg.byteBuf.skipBytes(4)
      msg.skipString
    }

    val channelName = if (tp == ChatEvents.CHAT_MSG_CHANNEL) {
      Some(msg.readString)
    } else {
      None
    }

    // ignore if from an unhandled channel - unless it is a guild achievement message
    if (tp != ChatEvents.CHAT_MSG_GUILD_ACHIEVEMENT && !Global.wowToDiscord.contains((tp, channelName.map(_.toLowerCase)))) {
      return None
    }

    msg.byteBuf.skipBytes(8) // skip guid again

    val txtLen = msg.byteBuf.readIntLE
    val txt = msg.byteBuf.readCharSequence(txtLen - 1, Charset.forName("UTF-8")).toString
    msg.byteBuf.skipBytes(1) // null terminator
    msg.byteBuf.skipBytes(1) // chat tag

    // invite feature:
    if (
      tp == ChatEvents.CHAT_MSG_WHISPER && (txt.toLowerCase.contains(
        "camp"
      ) || txt.toLowerCase().contains("invite"))
    ) {
      playersToGroupInvite += guid
      logger.debug(s"PLAYER INVITATION: added $guid to the queue")
    }

    if (tp == ChatEvents.CHAT_MSG_GUILD_ACHIEVEMENT) {
      handleAchievementEvent(guid, msg.byteBuf.readIntLE)
      None
    } else {
      Some(ChatMessage(guid, tp, txt, channelName))
    }
  }

  override protected def handleAchievementEvent(guid: Long, achievementId: Int): Unit = {
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
  
  private def handle_SMSG_CHANNEL_NOTIFY(msg: Packet): Unit = {
    val id = msg.byteBuf.readByte
    val channelName = msg.readString

    id match {
      case ChatNotify.CHAT_YOU_JOINED_NOTICE =>
        logger.info(s"Joined Channel: [$channelName]")
      case ChatNotify.CHAT_WRONG_PASSWORD_NOTICE =>
        logger.error(s"Wrong password for $channelName.")
      case ChatNotify.CHAT_MUTED_NOTICE =>
        logger.error(s"[$channelName] You do not have permission to speak.")
      case ChatNotify.CHAT_BANNED_NOTICE =>
        logger.error(s"[$channelName] You are banned from that channel.")
      case ChatNotify.CHAT_WRONG_FACTION_NOTICE =>
        logger.error(s"Wrong alliance for $channelName.")
      case ChatNotify.CHAT_INVALID_NAME_NOTICE =>
        logger.error("Invalid channel name")
      case ChatNotify.CHAT_THROTTLED_NOTICE =>
        logger.error(
          s"[$channelName] The number of messages that can be sent to this channel is limited, please wait to send another message."
        )
      case ChatNotify.CHAT_NOT_IN_AREA_NOTICE =>
        logger.error(
          s"[$channelName] You are not in the correct area for this channel."
        )
      case ChatNotify.CHAT_NOT_IN_LFG_NOTICE =>
        logger.error(
          s"[$channelName] You must be queued in looking for group before joining this channel."
        )
      case _ =>
      // ignore all other chat notifications
    }
  }

  private def handle_SMSG_NOTIFICATION(msg: Packet): Unit = {
    logger.info(s"Notification: ${parseNotification(msg)}")
  }

  override protected def parseNotification(msg: Packet): String = {
    msg.readString
  }

  // This is actually really hard to map back to a specific request
  // because the packet doesn't include a cookie/id/requested name if none found
  private def handle_SMSG_WHO(msg: Packet): Unit = {
    val displayResults = parseWhoResponse(msg)
    // Try to find exact match
    val exactName = CommandHandler.whoRequest.playerName.toLowerCase
    val exactMatch = displayResults.find(_.playerName.toLowerCase == exactName)
    val handledResponses = CommandHandler.handleWhoResponse(
      exactMatch,
      guildInfo,
      guildRoster,
      guildMember =>
        guildMember.name.equalsIgnoreCase(CommandHandler.whoRequest.playerName)
    )
    if (handledResponses.isEmpty) {
      // Exact match not found and no exact match in guild roster. Look for approximate matches.
      if (displayResults.isEmpty) {
        // No approximate matches found online. Try to find some in guild roster.
        val approximateMatches = CommandHandler.handleWhoResponse(
          exactMatch,
          guildInfo,
          guildRoster,
          guildMember => guildMember.name.toLowerCase.contains(exactName)
        )
        if (approximateMatches.isEmpty) {
          // No approximate matches found.
          CommandHandler.whoRequest.messageChannel
            .sendMessage(
              s"No player named ${CommandHandler.whoRequest.playerName} is currently playing."
            )
            .queue()
        } else {
          // Send at most 3 approximate matches.
          approximateMatches
            .take(3)
            .foreach(
              CommandHandler.whoRequest.messageChannel.sendMessage(_).queue()
            )
        }
      } else {
        // Approximate matches found online!
        displayResults
          .take(3)
          .foreach(whoResponse => {
            CommandHandler
              .handleWhoResponse(
                Some(whoResponse),
                guildInfo,
                guildRoster,
                guildMember =>
                  guildMember.name
                    .equalsIgnoreCase(CommandHandler.whoRequest.playerName)
              )
              .foreach(
                CommandHandler.whoRequest.messageChannel.sendMessage(_).queue()
              )
          })
      }
    } else {
      handledResponses.foreach(
        CommandHandler.whoRequest.messageChannel.sendMessage(_).queue()
      )
    }
  }

  override protected def parseWhoResponse(msg: Packet): Seq[WhoResponse] = {
    val displayCount = msg.byteBuf.readIntLE
    val matchCount = msg.byteBuf.readIntLE

    if (displayCount == 0) {
      Seq.empty
    } else {
      (0 until displayCount).map(i => {
        val playerName = msg.readString
        val guildName = msg.readString
        val lvl = msg.byteBuf.readIntLE
        val cls = Classes.valueOf(msg.byteBuf.readIntLE.toByte)
        val race = Races.valueOf(msg.byteBuf.readIntLE.toByte)
        val gender = if (WowChatConfig.getExpansion != WowExpansion.Vanilla) {
          Some(Genders.valueOf(msg.byteBuf.readByte)) // tbc/wotlk only
        } else {
          None
        }
        val zone = msg.byteBuf.readIntLE
        WhoResponse(
          playerName,
          guildName,
          lvl,
          cls,
          race,
          gender,
          GameResources.AREA.getOrElse(zone, "Unknown Zone")
        )
      })
    }
  }

  private def handle_SMSG_SERVER_MESSAGE(msg: Packet): Unit = {
    val tp = msg.byteBuf.readIntLE
    val txt = msg.readString
    val message = tp match {
      case ServerMessageType.SERVER_MSG_SHUTDOWN_TIME => s"Shutdown in $txt"
      case ServerMessageType.SERVER_MSG_RESTART_TIME  => s"Restart in $txt"
      case ServerMessageType.SERVER_MSG_SHUTDOWN_CANCELLED =>
        "Shutdown cancelled."
      case ServerMessageType.SERVER_MSG_RESTART_CANCELLED =>
        "Restart cancelled."
      case _ => txt
    }
    sendChatMessage(ChatMessage(0, ChatEvents.CHAT_MSG_SYSTEM, message, None))
  }

  private def handle_SMSG_INVALIDATE_PLAYER(msg: Packet): Unit = {
    val guid = parseInvalidatePlayer(msg)
    playerRoster.remove(guid)
  }

  override protected def parseInvalidatePlayer(msg: Packet): Long = {
    msg.byteBuf.readLongLE
  }

  private def handle_SMSG_WARDEN_DATA(msg: Packet): Unit = {
    if (Global.config.wow.platform == Platform.Windows) {
      logger.error(
        "WARDEN ON WINDOWS IS NOT SUPPORTED! BOT WILL SOON DISCONNECT! TRY TO USE PLATFORM MAC!"
      )
      return
    }

    if (wardenHandler.isEmpty) {
      wardenHandler = Some(initializeWardenHandler)
      logger.info("Warden handling initialized!")
    }

    val (id, out) = wardenHandler.get.handle(msg)
    if (out.isDefined) {
      ctx.get.writeAndFlush(Packet(CMSG_WARDEN_DATA, out.get))
      // Sometimes servers do not allow char listing request until warden is successfully answered.
      // Try requesting it here again.
      if (id == WardenPackets.WARDEN_SMSG_HASH_REQUEST) {
        sendCharEnum
      }
    }
  }

  override protected def initializeWardenHandler: WardenHandler = {
    new WardenHandler(sessionKey)
  }
}