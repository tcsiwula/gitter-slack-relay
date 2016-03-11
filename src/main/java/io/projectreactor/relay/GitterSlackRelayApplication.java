package io.projectreactor.relay;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.nio.NioEventLoopGroup;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SchedulerGroup;
import reactor.core.util.ExecutorUtils;
import reactor.io.buffer.Buffer;
import reactor.io.codec.json.JsonCodec;
import reactor.io.netty.http.HttpChannel;
import reactor.io.netty.http.model.Headers;
import reactor.io.netty.impl.netty.NettyClientSocketOptions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import static com.jayway.jsonpath.JsonPath.read;
import static reactor.io.netty.ReactiveNet.httpClient;

/**
 * A Spring Boot application that relays messages from a Gitter chat room to a Slack webhook to aggregate content into
 * a Slack channel.
 */
@SpringBootApplication
public class GitterSlackRelayApplication {
	/**
	 * Token used in the Authorization header sent to Gitter's streaming API.
	 */
	@Value("${gitter.token}")
	private String gitterToken;
	/**
	 * URL to connect to to stream Gitter messages from a chat room.
	 */
	@Value("https://stream.gitter.im/v1/rooms/${gitter.roomId}/chatMessages")
	private String gitterStreamUrl;
	/**
	 * URL to POST formatted messages to to appear in a Slack channel.
	 */
	@Value("${slack.webhookUrl}")
	private String slackWebhookUrl;

	/**
	 * Whether to shut this service down or not.
	 *
	 * @return
	 */
	@Bean
	public AtomicBoolean shutdownFlag() {
		return new AtomicBoolean(false);
	}

	/**
	 * A shared NioEventLoopGroup for reusing resources when creating new HTTP clients.
	 *
	 * @return
	 */
	@Bean
	public NioEventLoopGroup sharedEventLoopGroup() {
		return new NioEventLoopGroup(SchedulerGroup.DEFAULT_POOL_SIZE, ExecutorUtils.newNamedFactory("gitter-slack-relay"));
	}

	/**
	 * Reactor {@link reactor.io.netty.config.ClientSocketOptions} that pass the {@code sharedEventLoopGroup} to Netty.
	 *
	 * @return
	 */
	@Bean
	public NettyClientSocketOptions clientSocketOptions() {
		return new NettyClientSocketOptions().eventLoopGroup(sharedEventLoopGroup());
	}

	/**
	 * Handler for setting the Authorization and Accept headers and leaves the connection open by returning {@link
	 * Flux#never()}.
	 *
	 * @return
	 */
	@Bean
	public HttpChannel<Buffer, Buffer> gitterStreamHandler() {
		return ch -> {
			ch.header("Authorization", "Bearer " + gitterToken)
					.header("Accept", "application/json");
			return Mono.never();
		};
	}

	/**
	 * creates an HTTP client to connect to Gitter's streaming API.
	 *
	 * @return
	 */
	public Mono<Void> gitterSlackRelay() {
		return httpClient()
				.get(gitterStreamUrl, gitterStreamHandler())
				.flatMap(replies -> replies
						.input()
						.filter(b -> b.remaining() > 2) // ignore gitter keep-alives (\r)
						.map(new JsonCodec<>(Map.class).decoder()) // ObjectMapper.readValue(Map.class)
						.window(10, 1_000) // microbatch 10 items or 1s worth into individual streams (for reduce ops)
						.flatMap(w -> postToSlack(
										w.map(m -> formatLink(m) + ": " + formatText(m))
										.reduce("", GitterSlackRelayApplication::appendLines))
								)
				)
				.after(); // only complete when all windows have completed AND gitter GET connection has closed
	}

	private Mono<Void> postToSlack(Mono<String> input) {
		return httpClient(spec -> spec.options(clientSocketOptions()))
				.post(slackWebhookUrl, out ->
								out.header(Headers.CONTENT_TYPE, "application/json")
										.writeWith(input.map(s -> Buffer.wrap("{\"text\": \"" + s + "\"}")))
						//will close after write has flushed the batched window
				)
				.then(Mono::empty); //promote completion to returned promise when last reply has been consumed
		// (usually 1 from slack response packet)
	}

	public static void main(String[] args) throws Throwable {
		ApplicationContext ctx = SpringApplication.run(GitterSlackRelayApplication.class, args);
		GitterSlackRelayApplication app = ctx.getBean(GitterSlackRelayApplication.class);

		Flux
				.defer(app::gitterSlackRelay)
				.log("gitter-client-state")
				.repeat() //keep alive if get client closes
				.retry() //keep alive if any error
				.subscribe();

		CountDownLatch latch = new CountDownLatch(1);
		latch.await();

	}

	private static String formatDate(Object o) {
		DateTimeFormatter isoDateFmt = ISODateTimeFormat.dateTime();
		DateTimeFormatter shortFmt = DateTimeFormat.forPattern("d-MMM H:mm:ss");
		DateTime dte = isoDateFmt.parseDateTime((String) o);
		return shortFmt.print(dte);
	}

	private static String formatLink(Map m) {
		return "<https://gitter.im/reactor/reactor?at=" +
				read(m, "$.id") + "|" + read(m, "$.fromUser.displayName") +
				" [" + formatDate(read(m, "$.sent")) + "]>";
	}

	private static String formatText(Map m) {
		return ((String) read(m, "$.text")).replaceAll("\n", "\\\\n");
	}

	private static String appendLines(String prev, String next) {
		return (!"".equals(prev) ? prev + "\\\\n" + next : next);
	}

}
