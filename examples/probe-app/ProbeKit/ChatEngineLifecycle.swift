// SPDX-License-Identifier: MIT

import PhoneDSH

public enum ChatEngineLifecycle {
    public static func needsReset(afterStartIn state: PhoneDSHState) -> Bool {
        state == .failed
    }

    public static func canStartBySending(hostIsStopped: Bool,
                                         busy: Bool,
                                         parked: Bool,
                                         hostLifecycleTransition: Bool) -> Bool {
        hostIsStopped && !busy && !parked && !hostLifecycleTransition
    }
}
