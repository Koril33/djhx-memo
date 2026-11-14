//! Short-lived native installation barrier, separate from synchronization semantics.
use crate::{CoreError, Engine, MemoApp, model::AppError};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

#[derive(Default)]
pub(crate) struct ActivityGate {
    state: Mutex<(bool, usize)>,
    idle: Condvar,
}
pub(crate) struct ActivityLease(Arc<ActivityGate>);
impl Drop for ActivityLease {
    fn drop(&mut self) {
        if let Ok(mut state) = self.0.state.lock() {
            state.1 -= 1;
            self.0.idle.notify_all();
        }
    }
}
impl ActivityGate {
    pub(crate) fn enter(self: &Arc<Self>) -> crate::model::Result<ActivityLease> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| AppError::new("UPDATE_BUSY", "应用更新准备中，请稍后重试"))?;
        if state.0 {
            return Err(AppError::new("UPDATE_BUSY", "应用更新准备中，请稍后重试"));
        }
        state.1 += 1;
        Ok(ActivityLease(self.clone()))
    }
    fn pause(&self) -> bool {
        let Ok(mut state) = self.state.lock() else {
            return false;
        };
        if state.0 {
            return false;
        }
        state.0 = true;
        let deadline = Instant::now() + Duration::from_secs(40);
        while state.1 > 0 {
            let remaining = deadline.saturating_duration_since(Instant::now());
            let Ok((next, timeout)) = self.idle.wait_timeout(state, remaining) else {
                return false;
            };
            state = next;
            if timeout.timed_out() {
                state.0 = false;
                return false;
            }
        }
        true
    }
    fn resume(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.0 = false;
        }
    }
}

#[uniffi::export]
impl MemoApp {
    /// Waits for active transactions/network applications, then checkpoints every space.
    /// Call only after the native UI persisted its latest IME/editor buffer. Failure resumes work.
    pub fn pause_for_update(&self) -> std::result::Result<(), CoreError> {
        if !self.inner.activity.pause() {
            return Err(CoreError::Storage);
        }
        let result = self.inner.checkpoint_for_update();
        if result.is_err() {
            self.inner.activity.resume();
        }
        result
    }
    /// Releases a canceled installation barrier; no pending operation or note is discarded.
    pub fn resume_after_update(&self) {
        self.inner.activity.resume();
    }
}
impl Engine {
    fn checkpoint_for_update(&self) -> std::result::Result<(), CoreError> {
        let registry = self.registry.lock().map_err(|_| CoreError::Storage)?;
        for profile in &registry.profiles {
            let path = self.path(profile);
            if !path.exists() {
                continue;
            }
            // Do not migrate a dormant profile merely to checkpoint it.
            let db = rusqlite::Connection::open_with_flags(
                path,
                rusqlite::OpenFlags::SQLITE_OPEN_READ_WRITE,
            )
            .map_err(|_| CoreError::Storage)?;
            let busy: i64 = db
                .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| r.get(0))
                .map_err(|_| CoreError::Storage)?;
            if busy != 0 {
                return Err(CoreError::Storage);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod stream_tests;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pause_drains_existing_work_and_blocks_new_entries_until_resume() {
        let gate = Arc::new(ActivityGate::default());
        let active = gate.enter().unwrap();
        let worker = gate.clone();
        let paused = std::thread::spawn(move || worker.pause());
        while !gate.state.lock().unwrap().0 {
            std::thread::yield_now();
        }
        assert!(gate.enter().is_err());
        drop(active);
        assert!(paused.join().unwrap());
        assert!(gate.enter().is_err());
        gate.resume();
        assert!(gate.enter().is_ok());
    }
}
