package dev.ftb.mods.ftbteams.api.property;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * @author LatvianModder
 */
public class IntProperty extends TeamProperty<Integer> {
	public final int minValue;
	public final int maxValue;

	public IntProperty(ResourceLocation id, int def, int min, int max) {
		super(id, def);
		minValue = min;
		maxValue = max;
	}

	public IntProperty(ResourceLocation id, int def) {
		this(id, def, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	public IntProperty(ResourceLocation id, FriendlyByteBuf buf) {
		super(id, buf.readVarInt());
		minValue = buf.readVarInt();
		maxValue = buf.readVarInt();
	}

	@Override
	public TeamPropertyType<Integer> getType() {
		return TeamPropertyType.INT;
	}

	@Override
	public Optional<Integer> fromString(String string) {
		try {
			int num = Integer.parseInt(string);
			return Optional.of(Mth.clamp(num, minValue, maxValue));
		} catch (Exception ex) {
			return Optional.empty();
		}
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(defaultValue);
		buf.writeVarInt(minValue);
		buf.writeVarInt(maxValue);
	}

	@Override
	public void config(ConfigGroup config, TeamPropertyValue<Integer> value) {
		config.addInt(id.getPath(), value.value, value.consumer, defaultValue, minValue, maxValue);
	}

	@Override
	public Tag toNBT(Integer value) {
		return IntTag.valueOf(value);
	}

	@Override
	public Optional<Integer> fromNBT(Tag tag) {
		if (tag instanceof NumericTag) {
			return Optional.of(Mth.clamp(((NumericTag) tag).getAsInt(), minValue, maxValue));
		}

		return Optional.empty();
	}
}