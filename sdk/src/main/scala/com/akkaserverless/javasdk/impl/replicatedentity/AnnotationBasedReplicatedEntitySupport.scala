/*
 * Copyright 2021 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.akkaserverless.javasdk.impl.replicatedentity

import com.akkaserverless.javasdk.replicatedentity._
import com.akkaserverless.javasdk.impl.ReflectionHelper._
import com.akkaserverless.javasdk.impl._
import com.akkaserverless.javasdk.{EntityFactory, Metadata, Reply, ServiceCall, ServiceCallFactory}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import java.lang.reflect.{Constructor, Executable, InvocationTargetException}
import java.util.function.Consumer
import java.util.{function, Optional}
import scala.reflect.ClassTag

/**
 * Annotation based implementation of the [[ReplicatedEntityHandlerFactory]].
 */
private[impl] class AnnotationBasedReplicatedEntitySupport(
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[ReplicatedEntityCreationContext => AnyRef] = None
) extends ReplicatedEntityHandlerFactory
    with ResolvedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  def this(factory: EntityFactory, anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(factory.entityClass,
         anySupport,
         anySupport.resolveServiceDescriptor(serviceDescriptor),
         Some(context => factory.create(context)))

  private val constructor: ReplicatedEntityCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on Replicated Entity: $entityClass")
    }
  }

  private val commandHandlers = {
    val allMethods = ReflectionHelper.getAllDeclaredMethods(entityClass)

    ReflectionHelper.validateNoBadMethods(allMethods, classOf[ReplicatedEntity], Set(classOf[CommandHandler]))
    val handlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = resolvedMethods.getOrElse(name, {
          throw new RuntimeException(
            s"Command handler method ${method.getName} for command $name found, but the service has no command by that name."
          )
        })
        (ReflectionHelper.ensureAccessible(method), serviceMethod)
      }

    def getHandlers[C <: ReplicatedEntityContext with ReplicatedDataFactory: ClassTag] =
      handlers
        .filter(_._2.outputStreamed == false)
        .map {
          case (method, serviceMethod) =>
            new CommandHandlerInvoker[C](method,
                                         serviceMethod,
                                         anySupport,
                                         ReplicatedEntityAnnotationHelper.replicatedEntityParameterHandlers[C])
        }
        .groupBy(_.serviceMethod.name)
        .map {
          case (commandName, Seq(invoker)) => commandName -> invoker
          case (commandName, many) =>
            throw new RuntimeException(
              s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}"
            )
        }

    getHandlers[CommandContext]
  }

  override def create(context: ReplicatedEntityCreationContext): ReplicatedEntityHandler = {
    val entity = constructor(context)
    new EntityHandler(entity)
  }

  private class EntityHandler(entity: AnyRef) extends ReplicatedEntityHandler {

    override def handleCommand(command: JavaPbAny, context: CommandContext): Reply[JavaPbAny] = unwrap {
      val maybeResult = commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(
          s"No command handler found for command [${context.commandName()}] on Replicated Entity: $entityClass"
        )
      }
    }

    private def unwrap[T](block: => T): T =
      try {
        block
      } catch {
        case ite: InvocationTargetException if ite.getCause != null =>
          throw ite.getCause
      }
  }

}

private object ReplicatedEntityAnnotationHelper {
  private case class ReplicatedEntityInjector[C <: ReplicatedData, T](replicatedDataType: Class[C],
                                                                      create: ReplicatedDataFactory => T,
                                                                      wrap: C => T)
  private def simple[D <: ReplicatedData: ClassTag](create: ReplicatedDataFactory => D)() = {
    val clazz = implicitly[ClassTag[D]].runtimeClass.asInstanceOf[Class[D]]
    clazz -> ReplicatedEntityInjector[D, D](clazz, create, identity)
      .asInstanceOf[ReplicatedEntityInjector[ReplicatedData, AnyRef]]
  }

  // FIXME remove if we don't expose ReplicatedMap
  private def replicatedMapWrapper[W: ClassTag, D <: ReplicatedData](wrap: ReplicatedMap[AnyRef, D] => W) =
    implicitly[ClassTag[W]].runtimeClass
      .asInstanceOf[Class[D]] -> ReplicatedEntityInjector(classOf[ReplicatedMap[AnyRef, D]],
                                                          f => wrap(f.newReplicatedMap()),
                                                          wrap)
      .asInstanceOf[ReplicatedEntityInjector[ReplicatedData, AnyRef]]

  private val injectorMap: Map[Class[_], ReplicatedEntityInjector[ReplicatedData, AnyRef]] = Map(
    simple(_.newCounter()),
    simple(_.newReplicatedSet()),
    simple(_.newRegister()),
    simple(_.newReplicatedCounterMap()),
    simple(_.newReplicatedRegisterMap()),
    simple(_.newVote())
  )

  private def injector[D <: ReplicatedData, T](clazz: Class[T]): ReplicatedEntityInjector[D, T] =
    injectorMap.get(clazz) match {
      case Some(injector: ReplicatedEntityInjector[D, T] @unchecked) => injector
      case None => throw new RuntimeException(s"Don't know how to inject Replicated Data of type $clazz")
    }

  def replicatedEntityParameterHandlers[C <: ReplicatedEntityContext with ReplicatedDataFactory]
      : PartialFunction[MethodParameter, ParameterHandler[AnyRef, C]] = {
    case methodParam if injectorMap.contains(methodParam.parameterType) =>
      new ReplicatedEntityParameterHandler[C, ReplicatedData, AnyRef](injectorMap(methodParam.parameterType),
                                                                      methodParam.method)
    case methodParam
        if methodParam.parameterType == classOf[Optional[_]] &&
        injectorMap.contains(ReflectionHelper.getFirstParameter(methodParam.genericParameterType)) =>
      new OptionalReplicatedDataParameterHandler(
        injectorMap(ReflectionHelper.getFirstParameter(methodParam.genericParameterType)),
        methodParam.method
      )
  }

  private class ReplicatedEntityParameterHandler[C <: ReplicatedEntityContext with ReplicatedDataFactory,
                                                 D <: ReplicatedData,
                                                 T](
      injector: ReplicatedEntityInjector[D, T],
      method: Executable
  ) extends ParameterHandler[AnyRef, C] {
    override def apply(ctx: InvocationContext[AnyRef, C]): AnyRef = {
      val replicatedData = ctx.context.state(injector.replicatedDataType)
      if (replicatedData.isPresent) {
        injector.wrap(replicatedData.get()).asInstanceOf[AnyRef]
      } else {
        injector.create(ctx.context).asInstanceOf[AnyRef]
      }
    }
  }

  private class OptionalReplicatedDataParameterHandler[D <: ReplicatedData, T](injector: ReplicatedEntityInjector[D, T],
                                                                               method: Executable)
      extends ParameterHandler[AnyRef, ReplicatedEntityContext] {

    import scala.compat.java8.OptionConverters._
    override def apply(ctx: InvocationContext[AnyRef, ReplicatedEntityContext]): AnyRef =
      ctx.context.state(injector.replicatedDataType).asScala.map(injector.wrap).asJava
  }

}

private final class EntityConstructorInvoker(constructor: Constructor[_])
    extends (ReplicatedEntityCreationContext => AnyRef) {
  private val parameters =
    ReflectionHelper.getParameterHandlers[AnyRef, ReplicatedEntityCreationContext](constructor)(
      ReplicatedEntityAnnotationHelper.replicatedEntityParameterHandlers
    )
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type ${clazz.getName} in constructor")
    case _ =>
  }

  def apply(context: ReplicatedEntityCreationContext): AnyRef = {
    val ctx = InvocationContext(null.asInstanceOf[AnyRef], context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}
