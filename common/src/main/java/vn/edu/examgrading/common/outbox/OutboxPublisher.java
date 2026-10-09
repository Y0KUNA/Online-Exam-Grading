package vn.edu.examgrading.common.outbox;

public interface OutboxPublisher { void publish(OutboxMessage message); }
