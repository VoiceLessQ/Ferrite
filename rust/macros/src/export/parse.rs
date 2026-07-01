use itertools::Itertools as _;
use syn::ext::IdentExt as _;
use syn::parse::Parse;
use syn::punctuated::Punctuated;
use syn::{Ident, Token};

pub struct ExportPackage {
    // Parsed but only str is read today; kept to mirror BindPackage.
    #[allow(dead_code)]
    pub tokens: Punctuated<Ident, Token![.]>,
    pub str: String,
}

impl Parse for ExportPackage {
    fn parse(input: syn::parse::ParseStream) -> syn::Result<Self> {
        let tokens = input.parse_terminated(Ident::parse_any, Token![.])?;

        let str = tokens.iter().join(".");

        Ok(ExportPackage { tokens, str })
    }
}
