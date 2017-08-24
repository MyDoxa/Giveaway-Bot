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
package com.jagrosh.giveawaybot;

import com.jagrosh.giveawaybot.commands.*;
import com.jagrosh.giveawaybot.entities.Giveaway;
import com.jagrosh.giveawaybot.util.FormatUtil;
import com.jagrosh.jdautilities.commandclient.Command.Category;
import com.jagrosh.jdautilities.commandclient.CommandClient;
import com.jagrosh.jdautilities.commandclient.CommandClientBuilder;
import com.jagrosh.jdautilities.commandclient.examples.PingCommand;
import com.jagrosh.jdautilities.waiter.EventWaiter;
import net.dv8tion.jda.core.*;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.utils.SimpleLog;
import org.json.JSONException;
import org.json.JSONObject;

import javax.security.auth.login.LoginException;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This bot is designed to simplify giveaways. It is very easy to start a timed
 * giveaway, and easy for users to enter as well. This bot also automatically
 * picks a winner, and the winner can be re-rolled if necessary.
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class GiveawayBot {

    public static final OffsetDateTime START = OffsetDateTime.now();
    public static final String TADA = "\uD83C\uDF89";
    public static final String YAY = "<:yay:294906617378504704>";
    public static final Color BLURPLE = Color.decode("#7289DA");
    public static final String INVITE = "https://discordapp.com/oauth2/authorize?permissions=347200&scope=bot&client_id=294882584201003009";
    private static final String FILENAME = "giveaways_restart.txt";
    private static final Path configFile = Paths.get("config.json");
    // Giveaway category
    public static Category GIVEAWAY = new Category("Giveaway", event -> {
        if (event.getGuild() == null) {
            event.replyError("This command cannot be used in Direct Messages!");
            return false;
        }
        if (event.getMember().hasPermission(Permission.MANAGE_SERVER) || event.getMember().getRoles().stream().anyMatch(r -> r.getName().equalsIgnoreCase("giveaways")))
            return true;
        event.reply(event.getClient().getError() + " You must have the Manage Server permission, or a role called \"Giveaways\", to use this command!");
        return false;
    });
    // list of all logins the bot has
    private final List<JDA> shards;
    // threadPool to use for timings
    private final ScheduledExecutorService threadPool;
    // all current giveaways
    private final Set<Giveaway> giveaways;

    public GiveawayBot() {
        shards = new LinkedList<>();
        threadPool = Executors.newScheduledThreadPool(20);
        giveaways = new HashSet<>();
    }

    /**
     * Main execution
     *
     * @param args - number of shards to start with
     * @throws IOException              If one of the file read fails.
     * @throws LoginException           If the bot fails to login.
     * @throws IllegalArgumentException If one of the arguments used is invalid.
     * @throws RateLimitedException     If you have been rate limited and can't start the bot.
     * @throws JSONException            If there is a syntax error in the config file or a duplicated key.
     */
    public static void main(String[] args) throws IOException, LoginException, IllegalArgumentException, RateLimitedException, InterruptedException, JSONException {
        //Load config file
        JSONObject config;
        {
            List<String> configLines = Files.readAllLines(configFile);
            StringBuilder configContent = new StringBuilder();
            configLines.forEach(configContent::append);
            config = new JSONObject(configContent.toString());
        }

        // determine the number of shards
        int shards = args.length == 0 ? 1 : Integer.parseInt(args[0]);

        // load tokens from a file
        List<String> tokens = new ArrayList<>();
        config.getJSONArray("tokens").forEach(o -> tokens.add(o.toString()));

        // instantiate a bot
        GiveawayBot bot = new GiveawayBot();

        // instantiate an event waiter
        EventWaiter waiter = new EventWaiter();

        // build the client to deal with commands
        String prefix = config.has("prefix") ? config.getString("prefix") : "!g";
        CommandClient client = new CommandClientBuilder()
                .setPrefix(prefix)
                .setOwnerId(config.has("ownerId") ? config.getString("ownerId") : null)
                .setGame(Game.of("giveawaybot.party | " + prefix))
                .setEmojis(TADA, "\uD83D\uDCA5", "\uD83D\uDCA5")
                //.setServerInvite("https://discordapp.com/invite/0p9LSGoRLu6Pet0k")
                .setHelpFunction(FormatUtil::formatHelp)
                .setDiscordBotsKey(tokens.get(1))
                .addCommands(
                        new AboutCommand(bot),
                        new InviteCommand(),
                        new PingCommand(),

                        new CreateCommand(bot, waiter),
                        new StartCommand(bot),
                        new EndCommand(bot),
                        new RerollCommand(),

                        new EvalCommand(bot),
                        new ShutdownCommand(bot)
                ).build();

        for (int i = 0; i < shards; i++) {
            JDABuilder builder = new JDABuilder(AccountType.BOT)
                    .setToken(tokens.get(0))
                    .setAudioEnabled(false)
                    .setGame(Game.of("loading..."))
                    .setStatus(OnlineStatus.DO_NOT_DISTURB)
                    .addEventListener(client)
                    .addEventListener(waiter);
            if (shards > 1)
                builder.useSharding(i, shards);
            bot.addShard(builder.buildBlocking());
        }

        try {
            Files.readAllLines(Paths.get(FILENAME), Charset.forName("ISO-8859-1")).forEach(str -> {
                String[] parts = str.split(" {2}", 5);
                try {
                    Message m = bot.getTextChannelById(parts[0]).getMessageById(parts[1]).complete();
                    OffsetDateTime end = OffsetDateTime.parse(parts[2]);
                    int wins;
                    String prize;
                    try {
                        wins = Integer.parseInt(parts[3]);
                        prize = parts.length < 5 ? null : parts[4];
                    } catch (ArrayIndexOutOfBoundsException | NumberFormatException ex) {
                        wins = 1;
                        if (parts.length < 5)
                            prize = parts[3];
                        else
                            prize = parts[3] + "  " + parts[4];
                    }
                    if (prize != null && prize.equals("null"))
                        prize = null;
                    bot.getGiveaways().add(new Giveaway(bot, end, m, prize, wins));
                } catch (Exception e) {
                    SimpleLog.getLog("Loading").warn(e);
                }
            });
        } catch (IOException e) {
            SimpleLog.getLog("Loading").fatal(e);
        }
        long[] second = {0L};
        bot.getThreadPool().scheduleWithFixedDelay(() -> {
            try {
                OffsetDateTime now = OffsetDateTime.now();
                List<Giveaway> ends = new LinkedList<>();
                synchronized (bot.giveaways) {
                    for (Giveaway g : bot.getGiveaways()) {
                        if (g.getMessage() == null || now.plusSeconds(1).isAfter(g.getEnd())) {
                            ends.add(g);
                        } else if (second[0] % 60 == 0 || (second[0] % 5 == 0 && now.plusMinutes(5).isAfter(g.getEnd())) || (now.plusSeconds(5).isAfter(g.getEnd()))) {
                            g.updateMessage();
                        }
                    }
                }
                ends.forEach(Giveaway::end);
                second[0]++;
            } catch (Exception e) {
                SimpleLog.getLog("Counter").warn(e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        bot.getThreadPool().scheduleWithFixedDelay(bot::save, 5, 5, TimeUnit.MINUTES);
    }

    // private methods
    private void addShard(JDA shard) {
        shards.add(shard);
    }

    // public getters
    @SuppressWarnings("WeakerAccess")
    public ScheduledExecutorService getThreadPool() {
        return threadPool;
    }

    public Set<Giveaway> getGiveaways() {
        synchronized (giveaways) {
            return giveaways;
        }
    }

    public List<JDA> getShards() {
        return shards;
    }

    @SuppressWarnings("WeakerAccess")
    public TextChannel getTextChannelById(String id) {
        for (JDA shard : shards) {
            TextChannel tc = shard.getTextChannelById(id);
            if (tc != null)
                return tc;
        }
        return null;
    }

    public void shutdown() {
        threadPool.shutdown();
        save();
        shards.forEach(JDA::shutdown);
    }

    private void save() {
        StringBuilder builder = new StringBuilder();
        giveaways.forEach(g -> builder.append(g.getMessage().getChannel().getId()).append("  ").append(g.getMessage().getId()).append("  ")
                .append(g.getEnd().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("  ").append(g.getWinners()).append("  ").append(g.getPrize() == null ? "null" : g.getPrize().replace("\n", " ").replace("\r", "")).append("\n"));
        try {
            Files.write(Paths.get(FILENAME), builder.toString().trim().getBytes());
        } catch (Exception e) {
            SimpleLog.getLog("Saving").fatal(e);
        }
    }
}
