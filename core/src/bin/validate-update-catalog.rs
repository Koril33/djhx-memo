//! Offline publishing check using exactly the clients' pinned catalog verifier.
fn main() {
    let result = (|| {
        let mut args = std::env::args().skip(1);
        let path = args.next().ok_or("missing envelope path")?;
        let bytes = std::fs::read(path).map_err(|_| "UPDATE_STORAGE")?;
        let sequence = djhx_memo_core::validate_published_catalog(&bytes)?;
        while let Some(flag) = args.next() {
            if flag != "--asset" {
                return Err("invalid asset arguments".to_string());
            }
            let target = args.next().ok_or("missing target")?;
            let file = args.next().ok_or("missing asset")?;
            djhx_memo_core::validate_published_asset(&bytes, &target, std::path::Path::new(&file))?;
        }
        Ok(sequence)
    })();
    match result {
        Ok(sequence) => println!("Verified update catalog sequence {sequence}"),
        Err(code) => {
            eprintln!("{code}");
            std::process::exit(1);
        }
    }
}
