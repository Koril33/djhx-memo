export interface CoreError {
  code: string;
  message: string;
  retryable: boolean;
}
export interface Conflict {
  source_note_id: string;
  source_device_id: string;
  source_device_name: string;
  source_op_id: string;
  reason: 'stale_edit' | 'stale_delete' | 'edit_after_delete';
  detected_at: string;
}
export interface Note {
  id: string;
  content: string;
  title: string;
  generation: string;
  deleted: boolean;
  created_at: string;
  updated_at: string;
  conflict: Conflict | null;
  save_state: string;
  sync_state: string;
  redirected_from?: string;
}
export interface Profile {
  id: string;
  kind: 'local' | 'account' | 'recovery';
  label: string;
  server_url: string | null;
  user_id: string | null;
  username: string | null;
  device_id: string | null;
  read_only: boolean;
}
export interface SyncInfo {
  state:
    | 'local'
    | 'pending'
    | 'syncing'
    | 'synced'
    | 'offline'
    | 'auth_required'
    | 'recovery_required'
    | 'error';
  pending: number;
  last_success: string | null;
  error: CoreError | null;
}
export interface AppState {
  profile: Profile;
  profiles: Profile[];
  event_seq: string;
  sync: SyncInfo;
  credential_persistent: boolean;
  local_import_count: number;
  redirects?: Record<string, string>;
}
export interface Device {
  id: string;
  name: string;
  created_at: string;
  last_seen_at: string | null;
  expires_at: string;
  revoked_at: string | null;
  current: boolean;
}
export interface RuntimeInfo {
  tray_available: boolean;
  debug_http: boolean;
  version: string;
}
export type Request = { action: string; [key: string]: unknown };
export type CoreCall = <T>(request: Request) => Promise<T>;
