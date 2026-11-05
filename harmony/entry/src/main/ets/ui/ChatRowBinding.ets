// SPDX-License-Identifier: MIT
// Renderer-owned binding for one stable transcript row.
//
// The snapshot projector intentionally JSON-detaches all UI data, so a row is
// replaced by a fresh ChatRow object on every publish while its id stays
// stable. ForEach reuses a child for an unchanged key and does not re-run the
// item builder, which left the reused child holding the first ChatRow object.
// ChatView keeps one @Observed binding per stable id and mutates that binding
// on every snapshot; an @ObjectLink child observes the replacement even when
// ForEach reuses it. Snapshot state itself stays data-only.
import { ChatRow } from '../model/Contracts';

@Observed
export class ChatRowBinding {
  // Stable transcript identity. It is the ForEach key and never changes while
  // the row exists; the row object reference is allowed to change.
  id: string = '';
  // Latest detached row facts from the snapshot.
  row: ChatRow = new ChatRow();
  // Presentation facts ChatView derives from the snapshot and local fold state
  // on every publish. They live here so a reused child receives them too.
  showProcess: boolean = true;
  showTurnSummary: boolean = false;
  turnExpanded: boolean = false;
  copied: boolean = false;

  constructor(id: string, row: ChatRow) {
    this.id = id;
    this.row = row;
  }

  update(row: ChatRow, showProcess: boolean, showTurnSummary: boolean,
    turnExpanded: boolean, copied: boolean): void {
    this.row = row;
    this.showProcess = showProcess;
    this.showTurnSummary = showTurnSummary;
    this.turnExpanded = turnExpanded;
    this.copied = copied;
  }
}
