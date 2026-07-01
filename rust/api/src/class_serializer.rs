use std::fmt;

use serde::ser;

#[derive(Debug)]
pub struct Error(String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(&self.0, f)
    }
}

impl std::error::Error for Error {}

impl ser::Error for Error {
    fn custom<T>(msg: T) -> Self
    where
        T: fmt::Display,
    {
        Self(msg.to_string())
    }
}

pub struct ClassSerializer {
    buffer: Vec<u8>,
}

impl ser::Serializer for &mut ClassSerializer {
    type Ok = ();

    type Error = Error;

    type SerializeSeq = Self;
    type SerializeTuple = Self;
    type SerializeTupleStruct = Self;
    type SerializeTupleVariant = Self;
    type SerializeMap = Self;
    type SerializeStruct = Self;
    type SerializeStructVariant = Self;

    #[rustfmt::skip]
    fn serialize_bool(self, _: bool) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_i8(self, _: i8) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_i16(self, _: i16) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_i32(self, _: i32) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_i64(self, _: i64) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_f32(self, _: f32) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_f64(self, _: f64) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_char(self, _: char) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_str(self, _: &str) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_bytes(self, _: &[u8]) -> Result<Self::Ok, Self::Error> { unreachable!() }
    #[rustfmt::skip]
    fn serialize_none(self) -> Result<Self::Ok, Self::Error> { todo!() }

    fn serialize_some<T>(self, _: &T) -> Result<Self::Ok, Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        todo!()
    }

    #[rustfmt::skip]
    fn serialize_unit(self) -> Result<Self::Ok, Self::Error> { todo!() }
    #[rustfmt::skip]
    fn serialize_unit_struct(self, _: &'static str) -> Result<Self::Ok, Self::Error> { todo!() }
    #[rustfmt::skip]
    fn serialize_unit_variant(self, _: &'static str, _: u32, _: &'static str) -> Result<Self::Ok, Self::Error> { todo!() }

    fn serialize_newtype_struct<T>(self, _: &'static str, _: &T) -> Result<Self::Ok, Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        todo!()
    }

    #[rustfmt::skip]
    fn serialize_newtype_variant<T>(self, _: &'static str, _: u32, _: &'static str, _: &T) -> Result<Self::Ok, Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        todo!()
    }

    fn serialize_u8(self, v: u8) -> Result<Self::Ok, Self::Error> {
        self.buffer.extend_from_slice(&v.to_be_bytes());
        Ok(())
    }

    fn serialize_u16(self, v: u16) -> Result<Self::Ok, Self::Error> {
        self.buffer.extend_from_slice(&v.to_be_bytes());
        Ok(())
    }

    fn serialize_u32(self, v: u32) -> Result<Self::Ok, Self::Error> {
        self.buffer.extend_from_slice(&v.to_be_bytes());
        Ok(())
    }

    fn serialize_u64(self, v: u64) -> Result<Self::Ok, Self::Error> {
        self.buffer.extend_from_slice(&v.to_be_bytes());
        Ok(())
    }

    fn serialize_seq(self, len: Option<usize>) -> Result<Self::SerializeSeq, Self::Error> {
        let len = len.expect("all sequences should have len") as u16;
        self.buffer.extend_from_slice(&len.to_be_bytes());

        Ok(self)
    }

    fn serialize_tuple(self, _: usize) -> Result<Self::SerializeTuple, Self::Error> {
        Ok(self)
    }

    #[rustfmt::skip]
    fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeTupleStruct, Self::Error> {
        Ok(self)
    }

    #[rustfmt::skip]
    fn serialize_tuple_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<Self::SerializeTupleVariant, Self::Error> {
        Ok(self)
    }

    fn serialize_map(self, _: Option<usize>) -> Result<Self::SerializeMap, Self::Error> {
        Ok(self)
    }

    #[rustfmt::skip]
    fn serialize_struct(self, _: &'static str, _: usize) -> Result<Self::SerializeStruct, Self::Error> {
        Ok(self)
    }

    #[rustfmt::skip]
    fn serialize_struct_variant(self, _: &'static str, _: u32, _: &'static str, _: usize) -> Result<Self::SerializeStructVariant, Self::Error> {
        Ok(self)
    }
}

impl ser::SerializeSeq for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_element<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}
impl ser::SerializeTuple for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_element<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl ser::SerializeTupleStruct for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl ser::SerializeTupleVariant for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl ser::SerializeMap for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_key<T>(&mut self, _: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        Ok(())
    }

    fn serialize_value<T>(&mut self, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl ser::SerializeStruct for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, _: &'static str, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}

impl ser::SerializeStructVariant for &mut ClassSerializer {
    type Ok = ();
    type Error = Error;

    fn serialize_field<T>(&mut self, _: &'static str, value: &T) -> Result<(), Self::Error>
    where
        T: ?Sized + ser::Serialize,
    {
        value.serialize(&mut **self)
    }

    fn end(self) -> Result<Self::Ok, Self::Error> {
        Ok(())
    }
}
