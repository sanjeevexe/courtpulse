package com.courtpulse.persistence;

public record NotificationSettings(boolean inAppEnabled, boolean emailEnabled, String emailAddress) {}
