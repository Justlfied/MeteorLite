package net.runelite.mixins;

import net.runelite.api.ChatEntity;
import net.runelite.api.mixins.Inject;
import net.runelite.api.mixins.MethodHook;
import net.runelite.api.mixins.Mixin;
import net.runelite.api.mixins.Shadow;
import net.runelite.rs.api.RSClient;
import net.runelite.rs.api.RSUser;
import net.runelite.rs.api.RSUserList;
import net.runelite.rs.api.RSUsername;

import java.util.Arrays;

@Mixin(RSUserList.class)
public abstract class UserListMixin implements RSUserList
{
	@Shadow("client")
	private static RSClient client;

	/**
	 * Default implementation of rl$add
	 *
	 * @param name
	 * @param prevName
	 */
	@Inject
	@Override
	public void rl$add(RSUsername name, RSUsername prevName)
	{
	}

	/**
	 * Default implementation of rl$del
	 *
	 * @param nameable
	 */
	@Inject
	@Override
	public void rl$remove(RSUser nameable)
	{
	}

	@Inject
	@MethodHook(value = "addLast", end = true)
	public void add(RSUsername name, RSUsername prevName)
	{
		rl$add(name, prevName);
	}

	@Inject
	@MethodHook("remove")
	public void remove(RSUser nameable)
	{
		rl$remove(nameable);
	}

	@Inject
	@Override
	public ChatEntity[] getMembers()
	{
		ChatEntity[] chatEntities = this.getNameables();
		int count = this.getCount();
		return Arrays.copyOf(chatEntities, count);
	}

	@Inject
	@Override
	public ChatEntity findByName(String name)
	{
		return findByName(client.createName(name, client.getLoginType()));
	}
}
