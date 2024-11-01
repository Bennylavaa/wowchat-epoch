package wowchat.game

trait GamePacketsWotLK extends GamePacketsTBC {

  override val SMSG_GM_MESSAGECHAT = 0x03B3
  override val CMSG_KEEP_ALIVE = 0x0407
  override val CMSG_GROUP_INVITE = 0x6E
  override val SMSG_GROUP_INVITE = 0x6F
  override val CMSG_GROUP_KICK = 0x75
  override val CMSG_GROUP_KICK_UUID = 0x76
  override val SMSG_GROUP_KICK = 0x77
  override val CMSG_GROUP_RAID_CONVERT = 0x28E
  override val CMSG_GROUP_DISBAND = 0x7B
  override val SMSG_GROUP_LIST = 0x7D
  override val CMSG_LOOT_METHOD = 0x7A
  override val SMSG_PARTY_COMMAND_RESULT = 0x7F
  override val CMSG_GUILD_INVITE = 0x82
  override val SMSG_GUILD_INVITE = 0x83
  override val CMSG_GUILD_REMOVE = 0x8E
  override val CMSG_RESET_INSTANCES = 0x31D
}
