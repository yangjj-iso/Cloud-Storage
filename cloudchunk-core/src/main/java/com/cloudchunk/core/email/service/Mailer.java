package com.cloudchunk.core.email.service;

import com.cloudchunk.core.CloudchunkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 验证码邮件发送。SMTP 未启用时（默认）为开发模式：仅记录日志，不实际发送。
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final CloudchunkProperties.Smtp smtp;

    public Mailer(CloudchunkProperties properties) {
        this.smtp = properties.getSmtp();
    }

    public void sendCode(String to, String code, String type) {
        if (smtp == null || !smtp.isEnabled()) {
            log.info("[Mail dev] to={} type={} code={} (SMTP disabled, not actually sent)", to, type, code);
            return;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.getHost());
        sender.setPort(smtp.getPort());
        sender.setUsername(smtp.getUsername());
        sender.setPassword(smtp.getPassword());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(smtp.getFrom());
        msg.setTo(to);
        msg.setSubject("CloudChunk 验证码");
        msg.setText("你的验证码是 " + code + "，10 分钟内有效。（用途：" + type + "）");
        sender.send(msg);
    }
}
