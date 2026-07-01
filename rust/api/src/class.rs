use std::{marker::PhantomData, ops};

use jni::{
    objects::{JObject, JValueOwned},
    sys::jobject,
    JNIEnv,
};

use crate::{
    conversion::{FromJValue, IntoJValue},
    JSignature,
};

#[derive(Clone, Copy)]
pub struct Field<T>(
    pub(crate) jobject,
    pub(crate) &'static str,
    pub(crate) PhantomData<T>,
);

impl Field<()> {
    pub fn new<T>(instance: Instance, name: &'static str) -> Field<T> {
        Field(instance.into(), name, PhantomData)
    }
}

impl<T: JSignature + FromJValue> Field<T> {
    pub fn get<'local>(&self, env: &mut JNIEnv<'local>) -> T {
        let obj = unsafe { JObject::<'_>::from_raw(self.0) };
        let field = env.get_field(obj, self.1, T::sig()).unwrap();
        T::from_jvalue(field)
    }
}

pub struct StaticField<C, T>(
    pub(crate) &'static str,
    pub(crate) PhantomData<C>,
    pub(crate) PhantomData<T>,
);

impl<C, T> StaticField<C, T> {
    pub const fn new(field: &'static str) -> Self {
        Self(field, PhantomData, PhantomData)
    }
}

impl<C: JSignature, T: JSignature + FromJValue> StaticField<C, T> {
    pub fn get<'local>(&self, env: &mut JNIEnv<'local>) -> T {
        let field = env.get_static_field(C::CLASS, self.0, T::sig()).unwrap();
        T::from_jvalue(field)
    }
}

#[derive(Clone, Copy)]
pub struct Instance(pub jobject);

impl From<jobject> for Instance {
    fn from(value: jobject) -> Self {
        Self(value)
    }
}

impl From<Instance> for jobject {
    fn from(value: Instance) -> Self {
        value.0
    }
}

impl From<JObject<'_>> for Instance {
    fn from(value: JObject<'_>) -> Self {
        Self(*value)
    }
}

impl<'local> From<Instance> for JObject<'local> {
    fn from(value: Instance) -> Self {
        unsafe { JObject::<'_>::from_raw(value.0) }
    }
}

impl ops::Deref for Instance {
    type Target = jobject;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl IntoJValue for Instance {
    fn into_jvalue<'local>(self, _: &mut JNIEnv<'local>) -> JValueOwned<'local> {
        JValueOwned::Object(self.into())
    }
}

impl FromJValue for Instance {
    fn from_jvalue<'local>(value: JValueOwned<'local>) -> Self {
        Self(*value.l().expect("Instance should be an object"))
    }
}

impl Instance {
    pub fn cast<T: From<Instance>>(self) -> T {
        T::from(self)
    }
}
