// SPDX-License-Identifier: MIT

import Foundation

public struct RoutineClaim: Sendable, Equatable {
    public var routine: Routine
    public var claimed: Bool
    public var skipReason: String?
    public var deliveryKey: String?

    public init(routine: Routine, claimed: Bool, skipReason: String? = nil, deliveryKey: String? = nil) {
        self.routine = routine
        self.claimed = claimed
        self.skipReason = skipReason
        self.deliveryKey = deliveryKey
    }
}

public struct RoutineFinish: Sendable, Equatable {
    public var routine: Routine
    public var delivery: RoutineDelivery?
    public var ignored: Bool

    public init(routine: Routine, delivery: RoutineDelivery?, ignored: Bool = false) {
        self.routine = routine
        self.delivery = delivery
        self.ignored = ignored
    }
}

public struct RoutineRecoveryAction: Sendable, Equatable {
    public var action: String
    public var reason: String
    public var routineId: String
    public var attempt: Int
    public var runId: String
    public var deliveryKey: String?

    public init(action: String, reason: String, routineId: String, attempt: Int,
                runId: String, deliveryKey: String? = nil) {
        self.action = action
        self.reason = reason
        self.routineId = routineId
        self.attempt = attempt
        self.runId = runId
        self.deliveryKey = deliveryKey
    }
}

public struct RoutineDeliveryClaim: Sendable, Equatable {
    public enum State: String, Sendable {
        case claimed
        case missing
        case delivered
        case failed
        case busy
    }

    public var state: State
    public var delivery: RoutineDelivery?

    public init(state: State, delivery: RoutineDelivery? = nil) {
        self.state = state
        self.delivery = delivery
    }
}

public actor RoutineMachine {
    public let store: any RoutineStore

    public init(store: any RoutineStore) {
        self.store = store
    }

    public func list(botId: String) throws -> [Routine] {
        try store.read().routines
            .filter { $0.agentId == botId }
            .sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    public func get(botId: String, routineId: String) throws -> Routine? {
        try store.read().routines.first { $0.agentId == botId && $0.id == routineId }
    }

    @discardableResult
    public func create(botId: String, title: String, prompt: String, schedule: RoutineSchedule,
                       enabled: Bool = true, idempotent: Bool = false, urgent: Bool = false,
                       urgentReason: String = "", delivery: RoutineDeliveryMode = .held,
                       interruptReason: String = "", now: Int64, id: String? = nil) throws -> Routine {
        let routineId = id ?? "routine-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))"
        let next = enabled ? try RoutineScheduleMath.nextRunAt(schedule, now: now, createdAt: now) : nil
        let routine = try Routine(id: routineId, agentId: botId, title: title, prompt: prompt,
                                  schedule: schedule, enabled: enabled, idempotent: idempotent,
                                  urgent: urgent, urgentReason: urgentReason, delivery: delivery,
                                  interruptReason: interruptReason, createdAt: now, nextRunAt: next).validated()
        var document = try store.read()
        guard !document.routines.contains(where: { $0.agentId == botId && $0.id == routineId }) else {
            throw RoutineError.invalidRoutine("routine already exists: \(routineId)")
        }
        document.routines.append(routine)
        try store.write(document)
        return routine
    }

    @discardableResult
    public func update(botId: String, routineId: String, patch: RoutinePatch, now: Int64) throws -> Routine {
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else {
            throw RoutineError.routineNotFound(routineId)
        }
        let current = document.routines[index]
        var next = current
        if let title = patch.title { next.title = title }
        if let prompt = patch.prompt { next.prompt = prompt }
        if let schedule = patch.schedule { next.schedule = schedule }
        if let enabled = patch.enabled { next.enabled = enabled }
        if let idempotent = patch.idempotent { next.idempotent = idempotent }
        if let urgent = patch.urgent { next.urgent = urgent }
        if let urgentReason = patch.urgentReason { next.urgentReason = urgentReason }
        if let delivery = patch.delivery { next.delivery = delivery }
        if let interruptReason = patch.interruptReason { next.interruptReason = interruptReason }
        next = try next.validated()

        let scheduleChanged = patch.schedule != nil
        let reenabled = patch.enabled == true && !current.enabled
        if patch.enabled == false || scheduleChanged || reenabled {
            next.quietDefer = nil
        } else if patch.urgent == true, let deferred = next.quietDefer {
            next.quietDefer = nil
            next.nextRunAt = deferred.scheduledFor
        }
        if next.enabled {
            if scheduleChanged || reenabled || next.nextRunAt == nil {
                next.nextRunAt = try RoutineScheduleMath.nextRunAt(next.schedule, now: now,
                                                                   createdAt: next.createdAt, lastRunAt: next.lastRunAt)
            }
        } else {
            next.nextRunAt = nil
        }
        document.routines[index] = next
        try store.write(document)
        return next
    }

    @discardableResult
    public func delete(botId: String, routineId: String) throws -> Routine? {
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        let removed = document.routines.remove(at: index)
        try store.write(document)
        return removed
    }

    public func repairNextRun(botId: String, routineId: String, now: Int64) throws -> Routine? {
        guard var routine = try get(botId: botId, routineId: routineId), routine.nextRunAt == nil else {
            return try get(botId: botId, routineId: routineId)
        }
        routine.nextRunAt = routine.enabled ? try RoutineScheduleMath.nextRunAt(routine.schedule, now: now,
                                                                                createdAt: routine.createdAt,
                                                                                lastRunAt: routine.lastRunAt) : nil
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        document.routines[index] = routine
        try store.write(document)
        return routine
    }

    public func deferForQuiet(botId: String, routineId: String, until: Int64?, now: Int64) throws -> (routine: Routine, deferred: Bool)? {
        guard let until, until > now else {
            guard let routine = try get(botId: botId, routineId: routineId) else { return nil }
            return (routine, false)
        }
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        var routine = document.routines[index]
        guard routine.enabled else { return (routine, false) }
        let scheduledFor = routine.nextRunAt ?? now
        routine.quietDefer = RoutineQuietDefer(until: until, scheduledFor: scheduledFor, at: now)
        routine.nextRunAt = until
        document.routines[index] = routine
        try store.write(document)
        return (routine, true)
    }

    public func begin(botId: String, routineId: String, startedAt: Int64, scheduledFor: Int64? = nil,
                      leaseOwner: String = "local", leaseTTLMs: Int64? = nil, runId: String? = nil) throws -> RoutineClaim? {
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        var routine = document.routines[index]
        if let current = routine.runState, current.status == .started {
            routine.lastSkip = RoutineSkip(reason: "overlap", at: startedAt, triggerAt: routine.nextRunAt,
                                           runningAttempt: current.attempt, runningRunId: current.runId)
            routine.skipCount += 1
            document.routines[index] = routine
            try store.write(document)
            return RoutineClaim(routine: routine, claimed: false, skipReason: "overlap")
        }
        if routine.runState?.status == .needsReconciliation {
            return RoutineClaim(routine: routine, claimed: false, skipReason: "needs-reconciliation")
        }
        let attempt = routine.attempt + 1
        let resolvedRunId = runId ?? "run-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))"
        let deferred = routine.quietDefer
        let resolvedScheduledFor = deferred?.scheduledFor ?? scheduledFor ?? routine.nextRunAt ?? startedAt
        let state = RoutineRunState(runId: resolvedRunId, attempt: attempt, status: .started,
                                    scheduledFor: resolvedScheduledFor, startedAt: startedAt,
                                    leaseOwner: leaseOwner, leaseExpiresAt: leaseTTLMs.map { startedAt + $0 },
                                    quietDeferred: deferred)
        routine.attempt = attempt
        routine.quietDefer = nil
        routine.runState = state
        routine.lastRunAt = startedAt
        routine.lastResult = RoutineResult(status: .started, startedAt: startedAt,
                                           scheduledFor: resolvedScheduledFor, attempt: attempt, runId: resolvedRunId)
        document.routines[index] = routine
        try store.write(document)
        return RoutineClaim(routine: routine, claimed: true,
                            deliveryKey: RoutineDelivery.key(routineId: routine.id, attempt: attempt))
    }

    @discardableResult
    public func recordSkip(botId: String, routineId: String, reason: String = "overlap",
                           at: Int64, triggerAt: Int64? = nil) throws -> RoutineSkip? {
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        var routine = document.routines[index]
        let skip = RoutineSkip(reason: reason, at: at, triggerAt: triggerAt ?? routine.nextRunAt,
                               runningAttempt: routine.runState?.attempt, runningRunId: routine.runState?.runId)
        routine.lastSkip = skip
        routine.skipCount += 1
        document.routines[index] = routine
        try store.write(document)
        return skip
    }

    public func finish(botId: String, routineId: String, status: RoutineRunStatus,
                       text: String = "", error: String? = nil, startedAt: Int64? = nil,
                       finishedAt: Int64, attempt: Int? = nil, runId: String? = nil,
                       reconcileRequired: Bool = false, quiet: Bool = false,
                       cancelOrigin: String? = nil) throws -> RoutineFinish? {
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        var routine = document.routines[index]
        if let attempt, let current = routine.runState, current.attempt != attempt {
            let existing = document.deliveries[RoutineDelivery.key(routineId: routineId, attempt: current.attempt)]
            return RoutineFinish(routine: routine, delivery: existing, ignored: true)
        }
        if let runId, let current = routine.runState, current.runId != runId {
            let existing = document.deliveries[RoutineDelivery.key(routineId: routineId, attempt: current.attempt)]
            return RoutineFinish(routine: routine, delivery: existing, ignored: true)
        }
        let terminal = status.isTerminal ? status : .failed
        let current = routine.runState
        let resolvedAttempt = current?.attempt ?? attempt ?? routine.attempt
        let resolvedRunId = current?.runId ?? runId ?? "run-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))"
        let started = startedAt ?? current?.startedAt ?? routine.lastResult?.startedAt ?? finishedAt
        let scheduledFor = current?.scheduledFor ?? routine.nextRunAt ?? started
        let deferred = current?.quietDeferred
        let late = max(0, started - scheduledFor)
        let isQuiet = quiet || (terminal == .succeeded && text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        let state = RoutineRunState(runId: resolvedRunId, attempt: resolvedAttempt, status: terminal,
                                    scheduledFor: scheduledFor, startedAt: started, finishedAt: finishedAt,
                                    error: error.map { String($0.prefix(1000)) },
                                    reconciliationRequired: terminal == .needsReconciliation || reconcileRequired,
                                    quietDeferred: deferred)
        routine.runState = state
        routine.lastResult = RoutineResult(status: terminal, text: String(text.prefix(4000)),
                                           error: error.map { String($0.prefix(1000)) }, startedAt: started,
                                           finishedAt: finishedAt, durationMs: max(0, finishedAt - started),
                                           scheduledFor: scheduledFor, lateByMs: late, quiet: isQuiet,
                                           quietDeferredFrom: deferred?.scheduledFor, quietDeferredUntil: deferred?.until,
                                           attempt: resolvedAttempt, runId: resolvedRunId)
        routine.lastRunAt = started
        routine.crashRetry = false
        routine.quietDefer = nil
        routine.nextRunAt = terminal == .needsReconciliation
            ? routine.nextRunAt
            : try RoutineScheduleMath.nextAfterAttempt(routine, startedAt: started,
                                                       finishedAt: finishedAt, scheduledFor: scheduledFor)

        let key = RoutineDelivery.key(routineId: routine.id, attempt: resolvedAttempt)
        let delivery = document.deliveries[key] ?? RoutineDelivery(
            deliveryKey: key, routineId: routine.id, agentId: botId, attempt: resolvedAttempt,
            runId: resolvedRunId, resultStatus: terminal, text: String(text.prefix(4000)), error: error,
            routineTitle: routine.title, context: routine.prompt, delivery: routine.delivery,
            quiet: isQuiet, urgent: routine.urgent, urgentReason: routine.urgentReason,
            cancelOrigin: cancelOrigin, quietDeferredFrom: deferred?.scheduledFor,
            quietDeferredUntil: deferred?.until, scheduledFor: scheduledFor, lateByMs: late,
            startedAt: started, finishedAt: finishedAt, durationMs: max(0, finishedAt - started),
            nextRunAt: routine.nextRunAt, createdAt: finishedAt, updatedAt: finishedAt
        )
        document.deliveries[key] = delivery
        document.routines[index] = routine
        try store.write(document)
        return RoutineFinish(routine: routine, delivery: delivery)
    }

    public func recover(botId: String, now: Int64, leaseOwner: String? = nil,
                        maxAutoRetries: Int = 1) throws -> [RoutineRecoveryAction] {
        var document = try store.read()
        var actions: [RoutineRecoveryAction] = []
        var changed = false
        for index in document.routines.indices where document.routines[index].agentId == botId {
            var routine = document.routines[index]
            guard let current = routine.runState, current.status == .started else { continue }
            if let leaseOwner, current.leaseOwner == leaseOwner,
               let expires = current.leaseExpiresAt, expires > now { continue }
            let attempt = current.attempt
            let runId = current.runId
            let scheduledFor = current.scheduledFor ?? routine.nextRunAt ?? current.startedAt
            let canRetry = routine.idempotent && !routine.crashRetry && maxAutoRetries > 0
            if canRetry {
                let error = "routine attempt \(attempt) did not finish (crash); retrying because the routine is idempotent"
                routine.runState = RoutineRunState(runId: runId, attempt: attempt, status: .failed,
                                                   scheduledFor: scheduledFor, startedAt: current.startedAt,
                                                   finishedAt: now, error: error, recovered: true,
                                                   quietDeferred: current.quietDeferred)
                routine.lastResult = RoutineResult(status: .failed, error: error, startedAt: current.startedAt,
                                                   finishedAt: now, durationMs: max(0, now - current.startedAt),
                                                   scheduledFor: scheduledFor, attempt: attempt, runId: runId)
                routine.crashRetry = true
                routine.quietDefer = current.quietDeferred
                routine.nextRunAt = routine.enabled ? now : nil
                actions.append(RoutineRecoveryAction(action: "retry", reason: "routine-crash",
                                                     routineId: routine.id, attempt: attempt, runId: runId))
            } else {
                let error = "routine crashed before finishing; the result is unknown and was not replayed automatically"
                routine.runState = RoutineRunState(runId: runId, attempt: attempt, status: .needsReconciliation,
                                                   scheduledFor: scheduledFor, startedAt: current.startedAt,
                                                   finishedAt: now, error: error, reconciliationRequired: true,
                                                   recovered: true, quietDeferred: current.quietDeferred)
                routine.lastResult = RoutineResult(status: .needsReconciliation, error: error,
                                                   startedAt: current.startedAt, finishedAt: now,
                                                   durationMs: max(0, now - current.startedAt),
                                                   scheduledFor: scheduledFor, attempt: attempt, runId: runId)
                routine.crashRetry = false
                let key = RoutineDelivery.key(routineId: routine.id, attempt: attempt)
                if document.deliveries[key] == nil {
                    document.deliveries[key] = RoutineDelivery(
                        deliveryKey: key, routineId: routine.id, agentId: botId, attempt: attempt,
                        runId: runId, resultStatus: .needsReconciliation, error: error,
                        routineTitle: routine.title, context: routine.prompt, delivery: routine.delivery,
                        urgent: routine.urgent, urgentReason: routine.urgentReason,
                        quietDeferredFrom: current.quietDeferred?.scheduledFor,
                        quietDeferredUntil: current.quietDeferred?.until, scheduledFor: scheduledFor,
                        startedAt: current.startedAt, finishedAt: now,
                        durationMs: max(0, now - current.startedAt), nextRunAt: routine.nextRunAt,
                        createdAt: now, updatedAt: now
                    )
                }
                actions.append(RoutineRecoveryAction(action: "needs-reconciliation", reason: "routine-crash",
                                                     routineId: routine.id, attempt: attempt, runId: runId,
                                                     deliveryKey: key))
            }
            document.routines[index] = routine
            changed = true
        }
        if changed { try store.write(document) }
        return actions
    }

    public func resolveReconciliation(botId: String, routineId: String, decision: String,
                                      now: Int64) throws -> Routine? {
        guard decision == "retry" || decision == "acknowledge" else {
            throw RoutineError.unsupportedDecision(decision)
        }
        var document = try store.read()
        guard let index = document.routines.firstIndex(where: { $0.agentId == botId && $0.id == routineId }) else { return nil }
        var routine = document.routines[index]
        guard let state = routine.runState, state.status == .needsReconciliation else { return routine }
        routine.runState?.status = .failed
        routine.runState?.finishedAt = state.finishedAt ?? now
        routine.runState?.reconciliationRequired = false
        routine.runState?.recovered = true
        routine.lastResult?.status = .failed
        routine.lastResult?.error = "needs-reconciliation resolved by user: \(decision)"
        routine.lastResult?.finishedAt = now
        if routine.enabled {
            routine.nextRunAt = decision == "retry" ? now : try RoutineScheduleMath.nextAfterAttempt(
                routine, startedAt: state.startedAt, finishedAt: now, scheduledFor: state.scheduledFor)
        } else {
            routine.nextRunAt = nil
        }
        document.routines[index] = routine
        try store.write(document)
        return routine
    }

    public func delivery(_ key: String) throws -> RoutineDelivery? {
        try store.read().deliveries[key]
    }

    public func pendingDeliveries(botId: String, now: Int64, includeDelivering: Bool = false) throws -> [RoutineDelivery] {
        try store.read().deliveries.values.filter { delivery in
            guard delivery.agentId == botId else { return false }
            if delivery.status == .pending { return true }
            guard delivery.status == .delivering else { return false }
            return includeDelivering || delivery.leaseExpiresAt == nil || delivery.leaseExpiresAt! <= now
        }.sorted { ($0.createdAt, $0.deliveryKey) < ($1.createdAt, $1.deliveryKey) }
    }

    public func claimDelivery(_ key: String, leaseOwner: String = "local", now: Int64,
                              leaseTTLMs: Int64 = 60_000, force: Bool = false) throws -> RoutineDeliveryClaim {
        var document = try store.read()
        guard var delivery = document.deliveries[key] else { return RoutineDeliveryClaim(state: .missing) }
        if delivery.status == .delivered { return RoutineDeliveryClaim(state: .delivered, delivery: delivery) }
        if delivery.status == .failed { return RoutineDeliveryClaim(state: .failed, delivery: delivery) }
        if delivery.status == .delivering, !force, delivery.leaseOwner != leaseOwner,
           let expires = delivery.leaseExpiresAt, expires > now {
            return RoutineDeliveryClaim(state: .busy, delivery: delivery)
        }
        delivery.status = .delivering
        delivery.leaseOwner = leaseOwner
        delivery.leaseExpiresAt = now + max(1, leaseTTLMs)
        delivery.attempts += 1
        delivery.updatedAt = now
        document.deliveries[key] = delivery
        try store.write(document)
        return RoutineDeliveryClaim(state: .claimed, delivery: delivery)
    }

    public func completeDelivery(_ key: String, now: Int64) throws -> RoutineDelivery? {
        var document = try store.read()
        guard var delivery = document.deliveries[key] else { return nil }
        delivery.status = .delivered
        delivery.deliveredAt = now
        delivery.updatedAt = now
        delivery.leaseOwner = nil
        delivery.leaseExpiresAt = nil
        delivery.lastError = nil
        document.deliveries[key] = delivery
        try store.write(document)
        return delivery
    }

    public func failDelivery(_ key: String, error: String, now: Int64,
                             retryable: Bool = true, maxAttempts: Int = 3) throws -> RoutineDelivery? {
        var document = try store.read()
        guard var delivery = document.deliveries[key] else { return nil }
        let final = !retryable || delivery.attempts >= max(1, maxAttempts)
        delivery.status = final ? .failed : .pending
        delivery.lastError = String(error.prefix(1000))
        delivery.updatedAt = now
        delivery.leaseOwner = nil
        delivery.leaseExpiresAt = nil
        if final { delivery.failedAt = now }
        document.deliveries[key] = delivery
        try store.write(document)
        return delivery
    }

    @discardableResult
    public func markNotification(_ key: String, notified: String?, reason: String?, mode: String?,
                                 handled: Bool = true, now: Int64) throws -> RoutineDelivery? {
        var document = try store.read()
        guard var delivery = document.deliveries[key] else { return nil }
        delivery.notified = notified == "sent" || notified == "skipped" ? notified : nil
        delivery.notifyReason = reason.map { String($0.prefix(200)) }
        delivery.notifiedAt = delivery.notified == nil ? nil : now
        delivery.proactiveHandled = handled
        delivery.proactiveMode = mode
        delivery.updatedAt = now
        document.deliveries[key] = delivery
        try store.write(document)
        return delivery
    }

    @discardableResult
    public func markHeld(_ key: String, delivered: Bool, error: String? = nil,
                         now: Int64) throws -> RoutineDelivery? {
        var document = try store.read()
        guard var delivery = document.deliveries[key] else { return nil }
        delivery.heldInbox = delivered ? "delivered" : "failed"
        delivery.heldInboxError = delivered ? nil : String((error ?? "held inbox write failed").prefix(500))
        delivery.updatedAt = now
        document.deliveries[key] = delivery
        try store.write(document)
        return delivery
    }
}
