package com.dating.support.domain;

/** OPEN: waits for staff; ANSWERED: staff replied last; CLOSED: done (a new user message reopens it). */
public enum TicketStatus {
    OPEN, ANSWERED, CLOSED
}
