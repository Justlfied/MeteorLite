/*
 * Copyright (c) 2017, Devin French <https://github.com/devinfrench>
 * Copyright (c) 2019, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, trimbe <github.com/trimbe>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package meteor.plugins.chatchannel;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Runnables;
import com.google.inject.Provides;
import lombok.AllArgsConstructor;
import meteor.eventbus.events.ConfigChanged;
import net.runelite.api.*;
import net.runelite.api.clan.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetType;
import meteor.callback.ClientThread;
import meteor.chat.ChatMessageBuilder;
import meteor.chat.ChatMessageManager;
import meteor.chat.QueuedMessage;
import meteor.config.ChatColorConfig;
import meteor.config.ConfigManager;
import meteor.eventbus.Subscribe;
import meteor.game.ChatIconManager;
import meteor.game.chatbox.ChatboxPanelManager;
import meteor.plugins.Plugin;
import meteor.plugins.PluginDescriptor;
import meteor.util.Text;

import javax.inject.Inject;
import java.awt.*;
import java.util.List;
import java.util.*;

import static meteor.ui.JagexColors.*;

@PluginDescriptor(
	name = "Chat Channels",
	description = "Improvements for friends chat and clan chat."
)
public class ChatChannelPlugin extends Plugin
{
	private static final int MAX_CHATS = 10;
	private static final String RECENT_TITLE = "Recent FCs";
	private static final int MESSAGE_DELAY = 10;

	@Inject
	private Client client;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private ChatChannelConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ChatColorConfig chatColorConfig;

	@Inject
	private ChatMessageManager chatMessageManager;

	private List<String> chats;
	/**
	 * queue of temporary messages added to the client
	 */
	private final Deque<MemberJoinMessage> joinMessages = new ArrayDeque<>();
	private final Map<ChatPlayer, MemberActivity> activityBuffer = new LinkedHashMap<>();
	private int joinedTick;

	private boolean kickConfirmed = false;

	private boolean inputWarning;

	@AllArgsConstructor
	private enum InputMode
	{
		FRIEND("Friends Chat", ChatMessageType.FRIENDSCHAT),
		CLAN("Clan Chat", ChatMessageType.CLAN_CHAT),
		GUEST("Guest Clan Chat", ChatMessageType.CLAN_GUEST_CHAT);

		private final String prompt;
		private final ChatMessageType chatMessageType;
	}

	private InputMode inputMode;

	@Provides
	public ChatChannelConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChatChannelConfig.class);
	}

	@Override
	public void startup()
	{
		chats = new ArrayList<>(Text.fromCSV(config.chatsData()));

		if (config.showIgnores())
		{
			clientThread.invoke(() -> colorIgnoredPlayers(config.showIgnoresColor()));
		}
	}

	@Override
	public void shutdown()
	{
		chats = null;
		clientThread.invoke(() -> colorIgnoredPlayers(Color.WHITE));
		rebuildFriendsChat();

		if (inputMode != null)
		{
			clientThread.invoke(() ->
			{
				switchTypingMode(null);
				client.runScript(ScriptID.CHAT_PROMPT_INIT);
			});
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(ChatChannelConfig.GROUP))
		{
			if (!config.recentChats())
			{
				rebuildFriendsChat();
			}

			Color ignoreColor = config.showIgnores() ? config.showIgnoresColor() : Color.WHITE;
			clientThread.invoke(() -> colorIgnoredPlayers(ignoreColor));

			if (inputMode != null && !config.targetMode())
			{
				clientThread.invoke(() ->
				{
					switchTypingMode(null);
					client.runScript(ScriptID.CHAT_PROMPT_INIT);
				});
			}
		}
	}

	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		final FriendsChatMember member = event.getMember();

		// members getting initialized isn't relevant
		if (joinedTick == client.getTickCount())
		{
			return;
		}

		if (!config.showFriendsChatJoinLeave() ||
			member.getRank().getValue() < config.joinLeaveRank().getValue())
		{
			return;
		}

		// attempt to filter out world hopping joins
		queueJoin(member, MemberActivity.ChatType.FRIENDS_CHAT);
	}

	@Subscribe
	public void onFriendsChatMemberLeft(FriendsChatMemberLeft event)
	{
		final FriendsChatMember member = event.getMember();

		if (!config.showFriendsChatJoinLeave() ||
			member.getRank().getValue() < config.joinLeaveRank().getValue())
		{
			return;
		}

		queueLeave(member, MemberActivity.ChatType.FRIENDS_CHAT);
	}

	@Subscribe
	public void onClanMemberJoined(ClanMemberJoined clanMemberJoined)
	{
		MemberActivity.ChatType chatType = clanChannelToChatType(clanMemberJoined.getClanChannel());
		if (chatType != null && clanChannelJoinLeaveEnabled(chatType))
		{
			queueJoin(clanMemberJoined.getClanMember(), chatType);
		}
	}

	@Subscribe
	public void onClanMemberLeft(ClanMemberLeft clanMemberLeft)
	{
		MemberActivity.ChatType chatType = clanChannelToChatType(clanMemberLeft.getClanChannel());
		if (chatType != null && clanChannelJoinLeaveEnabled(chatType))
		{
			queueLeave(clanMemberLeft.getClanMember(), chatType);
		}
	}

	private MemberActivity.ChatType clanChannelToChatType(ClanChannel clanChannel)
	{
		return clanChannel == client.getClanChannel() ? MemberActivity.ChatType.CLAN_CHAT :
			clanChannel == client.getGuestClanChannel() ? MemberActivity.ChatType.GUEST_CHAT :
			null;
	}

	private boolean clanChannelJoinLeaveEnabled(MemberActivity.ChatType chatType)
	{
		switch (chatType)
		{
			case CLAN_CHAT:
				return config.clanChatShowJoinLeave();
			case GUEST_CHAT:
				return config.guestClanChatShowJoinLeave();
			default:
				return false;
		}
	}

	private void queueJoin(ChatPlayer member, MemberActivity.ChatType chatType)
	{
		// attempt to filter out world hopping joins
		if (!activityBuffer.containsKey(member))
		{
			MemberActivity joinActivity = new MemberActivity(ActivityType.JOINED, chatType,
				member, client.getTickCount());
			activityBuffer.put(member, joinActivity);
		}
		else
		{
			activityBuffer.remove(member);
		}
	}

	private void queueLeave(ChatPlayer member, MemberActivity.ChatType chatType)
	{
		if (!activityBuffer.containsKey(member))
		{
			MemberActivity leaveActivity = new MemberActivity(ActivityType.LEFT, chatType,
				member, client.getTickCount());
			activityBuffer.put(member, leaveActivity);
		}
		else
		{
			activityBuffer.remove(member);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Widget chatList = client.getWidget(WidgetInfo.FRIENDS_CHAT_LIST);
		if (chatList != null)
		{
			Widget owner = client.getWidget(WidgetInfo.FRIENDS_CHAT_OWNER);
			FriendsChatManager friendsChatManager = client.getFriendsChatManager();
			if ((friendsChatManager == null || friendsChatManager.getCount() <= 0)
				&& chatList.getChildren() == null && !Strings.isNullOrEmpty(owner.getText())
				&& config.recentChats())
			{
				loadFriendsChats();
			}
		}

		timeoutMessages();

		addActivityMessages();
	}

	private void timeoutMessages()
	{
		if (joinMessages.isEmpty())
		{
			return;
		}

		final int joinLeaveTimeout = config.joinLeaveTimeout();
		if (joinLeaveTimeout == 0)
		{
			return;
		}

		boolean removed = false;

		for (Iterator<MemberJoinMessage> it = joinMessages.iterator(); it.hasNext(); )
		{
			MemberJoinMessage joinMessage = it.next();
			MessageNode messageNode = joinMessage.getMessageNode();
			final int createdTick = joinMessage.getTick();

			if (client.getTickCount() > createdTick + joinLeaveTimeout)
			{
				it.remove();

				// If this message has been reused since, it will get a different id
				if (joinMessage.getGetMessageId() == messageNode.getId())
				{
					ChatLineBuffer ccInfoBuffer = client.getChatLineMap().get(messageNode.getType().getType());
					if (ccInfoBuffer != null)
					{
						ccInfoBuffer.removeMessageNode(messageNode);
						removed = true;
					}
				}
			}
			else
			{
				// Everything else in the deque is newer
				break;
			}
		}

		if (removed)
		{
			clientThread.invoke(() -> client.runScript(ScriptID.BUILD_CHATBOX));
		}
	}

	private void addActivityMessages()
	{
		if (activityBuffer.isEmpty())
		{
			return;
		}

		Iterator<MemberActivity> activityIt = activityBuffer.values().iterator();

		while (activityIt.hasNext())
		{
			MemberActivity activity = activityIt.next();

			if (activity.getTick() < client.getTickCount() - MESSAGE_DELAY)
			{
				activityIt.remove();
				switch (activity.getChatType())
				{
					case FRIENDS_CHAT:
						addActivityMessage((FriendsChatMember) activity.getMember(), activity.getActivityType());
						break;
					case CLAN_CHAT:
					case GUEST_CHAT:
						addClanActivityMessage((ClanChannelMember) activity.getMember(), activity.getActivityType(), activity.getChatType());
						break;
				}
			}
		}
	}

	private void addActivityMessage(FriendsChatMember member, ActivityType activityType)
	{
		final FriendsChatManager friendsChatManager = client.getFriendsChatManager();
		if (friendsChatManager == null)
		{
			return;
		}

		final String activityMessage = activityType == ActivityType.JOINED ? " has joined." : " has left.";
		final FriendsChatRank rank = member.getRank();
		final Color textColor, channelColor;
		int rankIcon = -1;

		// Use configured friends chat info colors if set, otherwise default to the jagex text and fc name colors
		if (client.isResized() && client.getVar(Varbits.TRANSPARENT_CHATBOX) == 1)
		{
			textColor = MoreObjects.firstNonNull(chatColorConfig.transparentFriendsChatInfo(), CHAT_FC_TEXT_TRANSPARENT_BACKGROUND);
			channelColor = MoreObjects.firstNonNull(chatColorConfig.transparentFriendsChatChannelName(), CHAT_FC_NAME_TRANSPARENT_BACKGROUND);
		}
		else
		{
			textColor = MoreObjects.firstNonNull(chatColorConfig.opaqueFriendsChatInfo(), CHAT_FC_TEXT_OPAQUE_BACKGROUND);
			channelColor = MoreObjects.firstNonNull(chatColorConfig.opaqueFriendsChatChannelName(), CHAT_FC_NAME_OPAQUE_BACKGROUND);
		}

		if (config.chatIcons() && rank != null && rank != FriendsChatRank.UNRANKED)
		{
			rankIcon = chatIconManager.getIconNumber(rank);
		}

		ChatMessageBuilder message = new ChatMessageBuilder()
			.append("[")
			.append(channelColor, friendsChatManager.getName());
		if (rankIcon > -1)
		{
			message
				.append(" ")
				.img(rankIcon);
		}
		message
			.append("] ")
			.append(textColor, member.getName$api() + activityMessage);

		final String messageString = message.build();
		final MessageNode line = client.addChatMessage(ChatMessageType.FRIENDSCHATNOTIFICATION, "", messageString, "");

		MemberJoinMessage joinMessage = new MemberJoinMessage(line, line.getId(), client.getTickCount());
		joinMessages.addLast(joinMessage);
	}

	private void addClanActivityMessage(ClanChannelMember member, ActivityType activityType, MemberActivity.ChatType chatType)
	{
		ClanSettings clanSettings = chatType == MemberActivity.ChatType.CLAN_CHAT ? client.getClanSettings() : client.getGuestClanSettings();
		ClanRank rank = member.getRank();

		if (rank == null || clanSettings == null)
		{
			return;
		}

		ClanTitle clanTitle = clanSettings.titleForRank(rank);
		int rankIcon = -1;
		if (clanTitle != null)
		{
			// Clan ranks are always included in chat messages, so we'll just always include it in join messages.
			rankIcon = chatIconManager.getIconNumber(clanTitle);
		}

		final Color textColor;
		// Use configured clan chat info colors if set, otherwise default to the jagex text and fc name colors
		if (client.isResized() && client.getVar(Varbits.TRANSPARENT_CHATBOX) == 1)
		{
			textColor = MoreObjects.firstNonNull(
				chatType == MemberActivity.ChatType.CLAN_CHAT ? chatColorConfig.transparentClanChatInfo() : chatColorConfig.transparentClanChatGuestInfo(),
				CHAT_FC_TEXT_TRANSPARENT_BACKGROUND);
		}
		else
		{
			textColor = MoreObjects.firstNonNull(
				chatType == MemberActivity.ChatType.CLAN_CHAT ? chatColorConfig.opaqueClanChatInfo() : chatColorConfig.opaqueClanChatGuestInfo(),
				CHAT_FC_TEXT_OPAQUE_BACKGROUND);
		}

		ChatMessageBuilder message = new ChatMessageBuilder();
		if (rankIcon > -1)
		{
			message.img(rankIcon);
		}
		message.append(textColor, member.getName$api() + (activityType == ActivityType.JOINED ? " has joined." : " has left."));

		final String messageString = message.build();
		final MessageNode line = client.addChatMessage(
			chatType == MemberActivity.ChatType.CLAN_CHAT ? ChatMessageType.CLAN_MESSAGE : ChatMessageType.CLAN_GUEST_MESSAGE,
			"", messageString, "");

		MemberJoinMessage joinMessage = new MemberJoinMessage(line, line.getId(), client.getTickCount());
		joinMessages.addLast(joinMessage);
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged strChanged)
	{
		if (strChanged.getIndex() == VarClientStr.RECENT_FRIENDS_CHAT.getIndex() && config.recentChats())
		{
			updateRecentChat(client.getVar(VarClientStr.RECENT_FRIENDS_CHAT));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if (client.getGameState() != GameState.LOADING && client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		FriendsChatManager friendsChatManager = client.getFriendsChatManager();
		if (friendsChatManager == null || friendsChatManager.getCount() == 0)
		{
			return;
		}

		switch (chatMessage.getType())
		{
			case PRIVATECHAT:
			case MODPRIVATECHAT:
				if (!config.privateMessageIcons())
				{
					return;
				}
				break;
			case PUBLICCHAT:
			case MODCHAT:
				if (!config.publicChatIcons())
				{
					return;
				}
				break;
			case FRIENDSCHAT:
				if (!config.chatIcons())
				{
					return;
				}
				break;
			default:
				return;
		}

		insertRankIcon(chatMessage);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged state)
	{
		GameState gameState = state.getGameState();

		if (gameState == GameState.LOGIN_SCREEN || gameState == GameState.CONNECTION_LOST || gameState == GameState.HOPPING)
		{
			joinMessages.clear();
		}
	}

	@Subscribe
	public void onFriendsChatChanged(FriendsChatChanged event)
	{
		if (event.isJoined())
		{
			joinedTick = client.getTickCount();
		}

		activityBuffer.clear();
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent)
	{
		switch (scriptCallbackEvent.getEventName())
		{
			case "confirmFriendsChatKick":
			{
				if (!config.confirmKicks() || kickConfirmed)
				{
					break;
				}

				// Set a flag so the script doesn't instantly kick them
				final int[] intStack = client.getIntStack();
				final int size = client.getIntStackSize();
				intStack[size - 1] = 1;

				// Get name of player we are trying to kick
				final String[] stringStack = client.getStringStack();
				final int stringSize = client.getStringStackSize();
				final String kickPlayerName = stringStack[stringSize - 1];

				// Show a chatbox panel confirming the kick
				clientThread.invokeLater(() -> confirmKickPlayer(kickPlayerName));
				break;
			}
			case "preChatSendpublic":
			{
				if (!config.targetMode())
				{
					return;
				}

				final String chatboxInput = client.getVar(VarClientStr.CHATBOX_TYPED_TEXT);
				switch (chatboxInput)
				{
					case "/p":
						switchTypingMode(null);
						break;
					case "/f":
						switchTypingMode(InputMode.FRIEND);
						break;
					case "/c":
						switchTypingMode(InputMode.CLAN);
						break;
					case "/g":
						switchTypingMode(InputMode.GUEST);
						break;
					default:
						if (inputMode != null)
						{
							final int[] intStack = client.getIntStack();
							final int intStackSize = client.getIntStackSize();
							intStack[intStackSize - 1] = inputMode.chatMessageType.getType(); // chat message type
							intStack[intStackSize - 2] = 0; // prefix length
						}
						break;
				}
				break;
			}
			case "setChatboxInput":
			{
				Widget chatboxInput = client.getWidget(WidgetInfo.CHATBOX_INPUT);
				if (chatboxInput != null && inputMode != null)
				{
					String text = chatboxInput.getText();
					int idx = text.indexOf(": ");
					if (idx != -1)
					{
						String newText = inputMode.prompt + ": " + text.substring(idx + 2);
						chatboxInput.setText(newText);
					}
				}
				break;
			}
		}
	}

	private void switchTypingMode(InputMode mode)
	{
		inputMode = mode;
		client.setVar(VarClientStr.CHATBOX_TYPED_TEXT, "");

		if (mode != null && !inputWarning)
		{
			inputWarning = true;

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage("You've entered " + inputMode.prompt + " typing mode. All typed messages will be sent to your " +
					inputMode.prompt.toLowerCase() + ". Use /p to reset to public chat.")
				.build());
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.FRIENDS_CHAT_CHANNEL_REBUILD)
		{
			if (config.showIgnores())
			{
				colorIgnoredPlayers(config.showIgnoresColor());
			}

			FriendsChatManager friendsChatManager = client.getFriendsChatManager();
			Widget chatTitle = client.getWidget(WidgetInfo.FRIENDS_CHAT_TITLE);
			if (friendsChatManager != null && friendsChatManager.getCount() > 0 && chatTitle != null)
			{
				chatTitle.setText(chatTitle.getText() + " (" + friendsChatManager.getCount() + "/100)");
			}
		}
	}

	private void insertRankIcon(final ChatMessage message)
	{
		final FriendsChatRank rank = getRank(Text.removeTags(message.getName()));

		if (rank != null && rank != FriendsChatRank.UNRANKED)
		{
			int iconNumber = chatIconManager.getIconNumber(rank);
			final String img = "<img=" + iconNumber + ">";
			if (message.getType() == ChatMessageType.FRIENDSCHAT)
			{
				message.getMessageNode()
					.setSender(message.getMessageNode().getSender() + " " + img);
			}
			else
			{
				message.getMessageNode()
					.setName(img + message.getMessageNode().getName());
			}
			client.refreshChat();
		}
	}

	private FriendsChatRank getRank(String playerName)
	{
		final FriendsChatManager friendsChatManager = client.getFriendsChatManager();
		if (friendsChatManager == null)
		{
			return FriendsChatRank.UNRANKED;
		}

		FriendsChatMember friendsChatMember = friendsChatManager.findByName(playerName);
		return friendsChatMember != null ? friendsChatMember.getRank() : FriendsChatRank.UNRANKED;
	}

	private void rebuildFriendsChat()
	{
		Widget chat = client.getWidget(WidgetInfo.FRIENDS_CHAT_ROOT);
		if (chat == null)
		{
			return;
		}

		Object[] args = chat.getOnVarTransmitListener();
		clientThread.invokeLater(() -> client.runScript(args));
	}

	private void loadFriendsChats()
	{
		Widget chatOwner = client.getWidget(WidgetInfo.FRIENDS_CHAT_OWNER);
		Widget chatList = client.getWidget(WidgetInfo.FRIENDS_CHAT_LIST);
		if (chatList == null || chatOwner == null)
		{
			return;
		}

		chatOwner.setText(RECENT_TITLE);

		int y = 2;
		chatList.setChildren(null);
		for (String chat : Lists.reverse(chats))
		{
			Widget widget = chatList.createChild(-1, WidgetType.TEXT);
			widget.setFontId(494);
			widget.setTextColor(0xffffff);
			widget.setText(chat);
			widget.setOriginalHeight(14);
			widget.setOriginalWidth(142);
			widget.setOriginalY(y);
			widget.setOriginalX(20);
			widget.revalidate();

			y += 14;
		}
	}

	private void updateRecentChat(String s)
	{
		if (Strings.isNullOrEmpty(s))
		{
			return;
		}

		s = Text.toJagexName(s);

		chats.removeIf(s::equalsIgnoreCase);
		chats.add(s);

		while (chats.size() > MAX_CHATS)
		{
			chats.remove(0);
		}

		config.chatsData(Text.toCSV(chats));
	}

	private void confirmKickPlayer(final String kickPlayerName)
	{
		chatboxPanelManager.openTextMenuInput("Attempting to kick: " + kickPlayerName)
			.option("1. Confirm kick", () ->
				clientThread.invoke(() ->
				{
					kickConfirmed = true;
					client.runScript(ScriptID.FRIENDS_CHAT_SEND_KICK, kickPlayerName);
					kickConfirmed = false;
				})
			)
			.option("2. Cancel", Runnables.doNothing())
			.build();
	}

	private void colorIgnoredPlayers(Color ignoreColor)
	{
		Widget chatList = client.getWidget(WidgetInfo.FRIENDS_CHAT_LIST);
		if (chatList == null || chatList.getChildren() == null)
		{
			return;
		}

		ChatEntityContainer<Ignore> ignoreContainer = client.getIgnoreContainer();
		// Iterate every 3 widgets, since the order of widgets is name, world, icon
		for (int i = 0; i < chatList.getChildren().length; i += 3)
		{
			Widget listWidget = chatList.getChild(i);
			String memberName = listWidget.getText();
			if (memberName.isEmpty() || ignoreContainer.findByName(memberName) == null)
			{
				continue;
			}

			listWidget.setTextColor(ignoreColor.getRGB());
		}
	}
}
