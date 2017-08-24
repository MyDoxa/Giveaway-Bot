/*
 * Copyright 2017 John Grosh (john.a.grosh@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.giveawaybot.commands;

import com.jagrosh.giveawaybot.GiveawayBot;
import com.jagrosh.giveawaybot.entities.Giveaway;
import com.jagrosh.giveawaybot.util.FinderUtil;
import com.jagrosh.giveawaybot.util.FormatUtil;
import com.jagrosh.jdautilities.commandclient.Command;
import com.jagrosh.jdautilities.commandclient.CommandEvent;
import com.jagrosh.jdautilities.waiter.EventWaiter;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class CreateCommand extends Command {

    private final static String CANCEL = "\n\n`Giveaway creation has been cancelled.`";
    private final static String CHANNEL = "\n\n`Please type the name of a channel in this server.`";
    private final static String TIME = "\n\n`Please enter the duration of the giveaway in seconds.`\n`Alternatively, enter a duration in minutes and include an M at the end.`";
    private final static String WINNERS = "\n\n`Please enter a number of winners between 1 and 15.`";
    private final static String PRIZE = "\n\n`Please enter the giveaway prize. This will also begin the giveaway.`";
    private final GiveawayBot bot;
    private final EventWaiter waiter;

    public CreateCommand(GiveawayBot bot, EventWaiter waiter) {
        this.bot = bot;
        this.waiter = waiter;
        name = "create";
        help = "creates a giveaway (interactive setup)";
        category = GiveawayBot.GIVEAWAY;
        guildOnly = true;
        //botPermissions = new Permission[]{Permission.MESSAGE_HISTORY,Permission.MESSAGE_ADD_REACTION,Permission.MESSAGE_EMBED_LINKS};
    }

    @Override
    protected void execute(CommandEvent event) {
        event.replySuccess("Alright! Let's set up your giveaway! First, what channel do you want the giveaway in?\n"
                + "You can type `cancel` at any time to cancel creation." + CHANNEL);
        waitForChannel(event);
    }

    private void waitForChannel(CommandEvent event) {
        waiter.waitForEvent(GuildMessageReceivedEvent.class,
                e -> e.getAuthor().equals(event.getAuthor()) && e.getChannel().equals(event.getChannel()),
                e -> {
                    if (e.getMessage().getRawContent().equalsIgnoreCase("cancel")) {
                        event.replyWarning("Alright, I guess we're not having a giveaway after all..." + CANCEL);
                    } else {
                        String query = e.getMessage().getRawContent().replace(" ", "_");
                        List<TextChannel> list = FinderUtil.findTextChannel(query, event.getGuild());
                        if (list.isEmpty()) {
                            event.replyWarning("Uh oh, I couldn't find any channels called '" + query + "'! Try again!" + CHANNEL);
                            waitForChannel(event);
                        } else if (list.size() > 1) {
                            event.replyWarning("Oh... there are multiple channels with that name. Please be more specific!" + CHANNEL);
                            waitForChannel(event);
                        } else {
                            TextChannel tchan = list.get(0);
                            if (!event.getSelfMember().hasPermission(tchan, Permission.MESSAGE_READ, Permission.MESSAGE_WRITE, Permission.MESSAGE_EMBED_LINKS)) {
                                event.replyWarning("Erm, I can't read, write, or embed links in " + tchan.getAsMention() + ". Please fix this and then try again." + CANCEL);
                            } else {
                                event.replySuccess("Sweet! The giveaway will be in " + tchan.getAsMention() + "! Next, how long should the giveaway last?" + TIME);
                                waitForTime(event, tchan);
                            }
                        }
                    }
                },
                2, TimeUnit.MINUTES, () -> event.replyWarning("Uh oh! You took longer than 2 minutes to respond, " + event.getAuthor().getAsMention() + "!" + CANCEL));
    }

    private void waitForTime(CommandEvent event, TextChannel tchan) {
        waiter.waitForEvent(GuildMessageReceivedEvent.class,
                e -> e.getAuthor().equals(event.getAuthor()) && e.getChannel().equals(event.getChannel()),
                e -> {
                    if (e.getMessage().getRawContent().equalsIgnoreCase("cancel")) {
                        event.replyWarning("Alright, I guess we're not having a giveaway after all..." + CANCEL);
                    } else {
                        String val = e.getMessage().getRawContent().toUpperCase().trim();
                        boolean min = false;
                        if (val.endsWith("M")) {
                            min = true;
                            val = val.substring(0, val.length() - 1).trim();
                        } else if (val.endsWith("S")) {
                            val = val.substring(0, val.length() - 1).trim();
                        }
                        int seconds;
                        try {
                            seconds = (min ? 60 : 1) * Integer.parseInt(val);
                            if (seconds < 10 || seconds > 60 * 60 * 24 * 7) {
                                event.replyWarning("Oh! Sorry! Giveaways need to be at least 10 seconds long, and can't be _too_ long. Mind trying again?" + TIME);
                                waitForTime(event, tchan);
                            } else {
                                event.replySuccess("Neat! This giveaway will last " + FormatUtil.secondsToTime(seconds) + "! Now, how many winners should there be?" + WINNERS);
                                waitForWinners(event, tchan, seconds);
                            }
                        } catch (NumberFormatException ex) {
                            event.replyWarning("Hm. I can't seem to get a number from that. Can you try again?" + TIME);
                            waitForTime(event, tchan);
                        }
                    }
                },
                2, TimeUnit.MINUTES, () -> event.replyWarning("Uh oh! You took longer than 2 minutes to respond, " + event.getAuthor().getAsMention() + "!" + CANCEL));
    }

    private void waitForWinners(CommandEvent event, TextChannel tchan, int seconds) {
        waiter.waitForEvent(GuildMessageReceivedEvent.class,
                e -> e.getAuthor().equals(event.getAuthor()) && e.getChannel().equals(event.getChannel()),
                e -> {
                    if (e.getMessage().getRawContent().equalsIgnoreCase("cancel")) {
                        event.replyWarning("Alright, I guess we're not having a giveaway after all..." + CANCEL);
                    } else {
                        try {
                            int num = Integer.parseInt(e.getMessage().getRawContent().trim());
                            if (num < 1 || num > 15) {
                                event.replyWarning("Hey! I can only support 1 to 15 winners!" + WINNERS);
                                waitForWinners(event, tchan, seconds);
                            } else {
                                event.replySuccess("Ok! " + num + " winners it is! Finally, what do you want to give away?" + PRIZE);
                                waitForPrize(event, tchan, seconds, num);
                            }
                        } catch (NumberFormatException ex) {
                            event.replyWarning("Uh... that doesn't look like a valid number." + WINNERS);
                            waitForWinners(event, tchan, seconds);
                        }
                    }
                },
                2, TimeUnit.MINUTES, () -> event.replyWarning("Uh oh! You took longer than 2 minutes to respond, " + event.getAuthor().getAsMention() + "!" + CANCEL));
    }

    private void waitForPrize(CommandEvent event, TextChannel tchan, int seconds, int winners) {
        waiter.waitForEvent(GuildMessageReceivedEvent.class,
                e -> e.getAuthor().equals(event.getAuthor()) && e.getChannel().equals(event.getChannel()),
                e -> {
                    if (e.getMessage().getRawContent().equalsIgnoreCase("cancel")) {
                        event.replyWarning("Alright, I guess we're not having a giveaway after all..." + CANCEL);
                    } else {
                        String prize = e.getMessage().getRawContent();
                        if (prize.length() > 500) {
                            event.replyWarning("Ack! That prize is too long. Can you shorten it a bit?" + PRIZE);
                            waitForPrize(event, tchan, seconds, winners);
                        } else {
                            try {
                                tchan.sendMessage(GiveawayBot.YAY + "   **GIVEAWAY**   " + GiveawayBot.YAY).queue(m -> {
                                            try {
                                                m.addReaction(GiveawayBot.TADA).queue();
                                            } catch (Exception ignored) {
                                            }
                                            event.replySuccess("Done! The giveaway for the `" + e.getMessage().getRawContent() + "` is starting in " + tchan.getAsMention() + "!");
                                            new Giveaway(bot, OffsetDateTime.now().plusSeconds(seconds), m, prize, winners).start();
                                        },
                                        v -> event.replyError("Uh oh. Something went wrong and I wasn't able to start the giveaway." + CANCEL));
                            } catch (Exception ex) {
                                event.replyError("Uh oh. Something went wrong and I wasn't able to start the giveaway." + CANCEL);
                            }

                        }
                    }
                },
                2, TimeUnit.MINUTES, () -> event.replyWarning("Uh oh! You took longer than 2 minutes to respond, " + event.getAuthor().getAsMention() + "!" + CANCEL));
    }
}
