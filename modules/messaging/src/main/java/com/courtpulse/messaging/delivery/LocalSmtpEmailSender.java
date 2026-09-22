package com.courtpulse.messaging.delivery;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Plain SMTP is deliberately restricted to the local Mailpit test sink. */
public final class LocalSmtpEmailSender implements EmailSender {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "mailpit");
    private final String host;
    private final int port;

    public LocalSmtpEmailSender(String host, int port) {
        if (!LOCAL_HOSTS.contains(host) || (port != 1025 && port != 11025)) {
            throw new IllegalArgumentException("Email is restricted to the local Mailpit SMTP port");
        }
        this.host = host;
        this.port = port;
    }

    @Override
    public String send(String address, String subject, String body) {
        if (address == null || !address.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")) {
            throw new EmailSendException("invalid_recipient", false);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            expect(input, 220);
            command(output, input, "EHLO courtpulse.local", 250);
            command(output, input, "MAIL FROM:<alerts@courtpulse.test>", 250);
            command(output, input, "RCPT TO:<" + address + ">", 250);
            command(output, input, "DATA", 354);
            write(output, "From: CourtPulse <alerts@courtpulse.test>\r\n");
            write(output, "To: <" + address + ">\r\n");
            write(output, "Subject: " + safeLine(subject) + "\r\n");
            write(output, "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
            for (String line : body.replace("\r", "").split("\n", -1)) {
                write(output, (line.startsWith(".") ? "." : "") + safeLine(line) + "\r\n");
            }
            write(output, ".\r\n");
            output.flush();
            expect(input, 250);
            command(output, input, "QUIT", 221);
            return null; // Mailpit SMTP does not return a durable provider message ID.
        } catch (EmailSendException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new EmailSendException("local_smtp_unavailable", true);
        }
    }

    private static void command(BufferedWriter output, BufferedReader input, String command, int code)
            throws IOException {
        write(output, command + "\r\n");
        output.flush();
        expect(input, code);
    }

    private static void expect(BufferedReader input, int expected) throws IOException {
        String line;
        do {
            line = input.readLine();
            if (line == null || line.length() < 4 || !line.substring(0, 3).matches("[0-9]{3}")) {
                throw new EmailSendException("smtp_protocol_error", true);
            }
            int code = Integer.parseInt(line.substring(0, 3));
            if (code != expected) {
                throw new EmailSendException(code >= 500 ? "smtp_permanent_rejection" : "smtp_temporary_rejection",
                        code < 500);
            }
        } while (line.charAt(3) == '-');
    }

    private static void write(BufferedWriter output, String value) throws IOException {
        output.write(value);
    }

    private static String safeLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\p{Cntrl}]", " ").strip();
    }
}
