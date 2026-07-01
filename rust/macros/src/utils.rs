use proc_macro2::Span;
use quote::ToTokens as _;
use syn::spanned::Spanned as _;
use syn::{Attribute, Error, Expr, ExprLit, Lit, LitStr, Meta, MetaList, MetaNameValue};

pub fn get_string_attr(
    span: Span,
    attr_name: &impl AsRef<str>,
    attrs: &[Attribute],
) -> Result<Option<String>, Error> {
    let attr = get_attribute(attr_name, attrs).map(|attr| match &attr.meta {
        Meta::List(MetaList { tokens, .. }) => {
            Ok(syn::parse2::<LitStr>(tokens.to_token_stream())?.value())
        }
        Meta::NameValue(MetaNameValue {
            value: Expr::Lit(ExprLit {
                lit: Lit::Str(p), ..
            }),
            ..
        }) => Ok(p.value()),
        _ => {
            attr.meta
                .span()
                .unwrap()
                .error("expected string attribute")
                .help(format!("Usage: #[{} = \"value\"]", attr_name.as_ref()))
                .emit();

            Err(Error::new(
                span,
                format!("Usage: #[{} = \"value\"]", attr_name.as_ref()),
            ))
        }
    });

    if let Some(attr) = attr {
        Ok(Some(attr?))
    } else {
        Ok(None)
    }
}

pub fn get_rename_attr(span: Span, attrs: &[Attribute]) -> Result<Option<String>, Error> {
    get_string_attr(span, &"rename", attrs)
}

pub fn get_attribute<'a>(
    attr_name: &impl AsRef<str>,
    attrs: &'a [Attribute],
) -> Option<&'a Attribute> {
    attrs.iter().find(|attr| attr.path().is_ident(attr_name))
}
