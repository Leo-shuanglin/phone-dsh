// SPDX-License-Identifier: MIT
package com.phonedsh.ui.nav

import com.phonedsh.ui.state.PendingAnswer

/** Live host operations for the native session; rendering never owns the transport. */
public data class ChatInteractionActions(
    public val toggleRow: (String) -> Unit = {},
    public val approve: (PendingAnswer) -> Unit = {},
    public val deny: (PendingAnswer) -> Unit = {},
    public val answer: (PendingAnswer, String) -> Unit = { _, _ -> },
    public val dismissQuestion: (PendingAnswer) -> Unit = {},
    public val goalCommand: ((String) -> Unit)? = null,
    public val selectAccessMode: (String) -> Unit = {},
    public val refreshCatalogs: () -> Unit = {},
    public val imeChanged: (Boolean) -> Unit = {},
    public val expandPending: () -> Unit = {},
    public val startEngine: () -> Unit = {},
    public val executeCommand: ((String) -> Unit)? = null,
    public val selectModel: (String, String?) -> Unit = { _, _ -> },
    public val restartEngine: () -> Unit = {},
)
