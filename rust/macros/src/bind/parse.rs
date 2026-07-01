use itertools::Itertools;
use proc_macro2::Span;
use syn::ext::IdentExt as _;
use syn::parse::Parse;
use syn::punctuated::Punctuated;
use syn::{braced, parenthesized, Ident, ReturnType, TypePath};
use syn::{token, LitStr};
use syn::{Attribute, Token};

use crate::syn_ext::ParseStreamExt as _;

pub struct BindInput {
    pub package: BindPackage,
    pub defs: Vec<BindDefinition>,
}

impl Parse for BindInput {
    fn parse(input: syn::parse::ParseStream) -> syn::Result<Self> {
        let package = input.parse()?;

        let mut defs = vec![];

        loop {
            if input.is_empty() {
                break;
            }

            defs.push(BindDefinition::parse(input)?);
        }

        Ok(BindInput { package, defs })
    }
}

pub struct BindPackage {
    pub use_: Token![use],
    pub tokens: Punctuated<Ident, Token![.]>,
    pub semi: Token![;],
    pub str: String,
}

impl Parse for BindPackage {
    fn parse(input: syn::parse::ParseStream) -> syn::Result<Self> {
        if input.peek(Token![use]) {
            let use_ = input.parse()?;
            let tokens = input.parse_separated_until::<Token![;], _, _>(Ident::parse_any)?;
            let semi = input.parse()?;

            let str = tokens.iter().join(".");

            Ok(BindPackage {
                use_,
                tokens,
                semi,
                str,
            })
        } else {
            Err(input.error("expected package definition"))
        }
    }
}

pub enum BindDefinition {
    Class {
        attributes: Vec<Attribute>,
        impl_: Token![impl],
        class: Ident,
        fields_open: token::Brace,
        fields: Vec<BindField>,
    },
    Enum {
        attributes: Vec<Attribute>,
        enum_: Token![enum],
        enum_of: Option<(Token![:], TypePath)>,
        name: Ident,
        variants_open: token::Brace,
        variants: Punctuated<BindVariant, Token![,]>,
    },
}

impl Parse for BindDefinition {
    fn parse(input: syn::parse::ParseStream) -> syn::Result<Self> {
        let attributes = input.call(Attribute::parse_outer)?;

        let lookahead = input.lookahead1();

        if lookahead.peek(Token![impl]) {
            let content;
            Ok(BindDefinition::Class {
                attributes,
                impl_: input.parse()?,
                class: input.parse()?,
                fields_open: braced!(content in input),
                fields: {
                    let mut fields = vec![];

                    loop {
                        if content.is_empty() {
                            break;
                        }

                        fields.push(BindField::parse(&content)?);
                        content.parse::<Token![;]>()?;
                    }

                    fields
                },
            })
        } else if lookahead.peek(Token![enum]) {
            let content;
            Ok(BindDefinition::Enum {
                attributes,
                enum_: input.parse()?,
                name: input.parse()?,
                enum_of: input.parse_if(
                    |input| input.peek(Token![:]),
                    |input| Ok((input.parse()?, input.parse()?)),
                )?,
                variants_open: braced!(content in input),
                variants: content.parse_terminated(BindVariant::parse, Token![,])?,
            })
        } else {
            Err(lookahead.error())
        }
    }
}

pub enum BindField {
    Property(BindFieldProperty),
    Method(BindFieldMethod),
}

impl Parse for BindField {
    fn parse(input: syn::parse::ParseStream) -> syn::Result<Self> {
        let attributes = input.call(Attribute::parse_outer)?;

        let lookahead = input.lookahead1();

        if lookahead.peek(Token![static]) {
            Ok(BindField::Property(BindFieldProperty::parse(
                input, attributes, true,
            )?))
        } else if lookahead.peek(Token![let]) {
            Ok(BindField::Property(BindFieldProperty::parse(
                input, attributes, false,
            )?))
        } else if lookahead.peek(Token![fn]) {
            Ok(BindField::Method(BindFieldMethod::parse(
                input, attributes,
            )?))
        } else {
            Err(lookahead.error())
        }
    }
}

pub struct BindFieldProperty {
    pub attributes: Vec<Attribute>,
    pub is_static: bool,
    pub prop_def: Span,
    pub ident: Ident,
    pub colon: Token![:],
    pub ty: TypePath,
}

impl BindFieldProperty {
    fn parse(
        input: syn::parse::ParseStream,
        attributes: Vec<Attribute>,
        is_static: bool,
    ) -> syn::Result<Self> {
        Ok(BindFieldProperty {
            attributes,
            is_static,
            prop_def: if is_static {
                input.parse::<Token![static]>()?.span
            } else {
                input.parse::<Token![let]>()?.span
            },
            ident: input.parse()?,
            colon: input.parse()?,
            ty: input.parse()?,
        })
    }
}

pub struct BindFieldMethod {
    pub attributes: Vec<Attribute>,
    pub is_static: bool,
    pub name: Ident,
    pub params_open: token::Paren,
    pub params: Punctuated<(Ident, Token![:], TypePath), Token![,]>,
    pub ret: ReturnType,
}

impl BindFieldMethod {
    fn parse(input: syn::parse::ParseStream, attributes: Vec<Attribute>) -> syn::Result<Self> {
        input.parse::<Token![fn]>()?;

        let name = input.parse()?;

        let params;
        let params_open = parenthesized!(params in input);

        let is_static = if params.peek(Token![self]) {
            params.parse::<Token![self]>()?;

            if !params.is_empty() {
                params.parse::<Token![,]>()?;
            }

            false
        } else {
            true
        };

        let params = params.parse_terminated(
            |param| Ok((param.parse()?, param.parse()?, param.parse()?)),
            Token![,],
        )?;

        Ok(BindFieldMethod {
            attributes,
            is_static,
            name,
            params_open,
            params,
            ret: input.parse()?,
        })
    }
}

pub struct BindVariant {
    pub ident: Ident,
    pub rename: Option<(Token![=], LitStr)>,
}

impl Parse for BindVariant {
    fn parse(input: syn::parse::ParseStream) -> syn::Result<Self> {
        Ok(Self {
            ident: input.parse()?,
            rename: input.parse_if(
                |input| input.peek(Token![=]),
                |input| Ok((input.parse()?, input.parse()?)),
            )?,
        })
    }
}
