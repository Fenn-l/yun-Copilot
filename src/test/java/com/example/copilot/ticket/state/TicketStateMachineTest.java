package com.example.copilot.ticket.state;

import com.example.copilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketStateMachineTest {

    @Test
    void allowedTransitions() {
        assertTrue(TicketStateMachine.canTransit("NEW", "ASSIGNED"));
        assertTrue(TicketStateMachine.canTransit("ASSIGNED", "IN_PROGRESS"));
        assertTrue(TicketStateMachine.canTransit("IN_PROGRESS", "WAITING_USER"));
        assertTrue(TicketStateMachine.canTransit("WAITING_USER", "IN_PROGRESS"));
        assertTrue(TicketStateMachine.canTransit("IN_PROGRESS", "RESOLVED"));
        assertTrue(TicketStateMachine.canTransit("RESOLVED", "CLOSED"));
        assertTrue(TicketStateMachine.canTransit("CLOSED", "IN_PROGRESS"));
        assertTrue(TicketStateMachine.canTransit("RESOLVED", "IN_PROGRESS"));
    }

    @Test
    void illegalTransitions() {
        assertFalse(TicketStateMachine.canTransit("NEW", "CLOSED"));
        assertFalse(TicketStateMachine.canTransit("NEW", "IN_PROGRESS"));
        assertFalse(TicketStateMachine.canTransit("ASSIGNED", "RESOLVED"));
        assertFalse(TicketStateMachine.canTransit("CLOSED", "WAITING_USER"));
        assertFalse(TicketStateMachine.canTransit("RESOLVED", "ASSIGNED"));
        assertThrows(BusinessException.class,
                () -> TicketStateMachine.assertTransition("NEW", "RESOLVED"));
    }

    @Test
    void sameStatusKeepsIdempotent() {
        assertDoesNotThrow(() -> TicketStateMachine.assertTransition("ASSIGNED", "ASSIGNED"));
    }
}
