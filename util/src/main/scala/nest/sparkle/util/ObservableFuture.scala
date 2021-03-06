/* Copyright 2013  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.util

import java.util.NoSuchElementException

import rx.lang.scala.Observable

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** convert a scala Future to an Observable and back */
object ObservableFuture {

  implicit class WrappedFuture[T](val future: Future[T]) {

    /** return an observable that will return a single value when the future completes */
    def toObservable(implicit executionContext: ExecutionContext): Observable[T] =
      Observable.from(future)(executionContext)
  }

  implicit class WrappedObservable[T](val observable: Observable[T]) {

    /** return an Future that will return a single sequence for the observable stream */
    def toFutureSeq: Future[Seq[T]] = {
      val promise = Promise[Seq[T]]()
      def onNext(value: Seq[T]): Unit = {
        promise.complete(Success(value))
      }

      def onError(error: Throwable): Unit = {
         promise.complete(Failure(error))
      }

      def onCompleted(): Unit = {
        if (!promise.isCompleted) {
          promise.complete(Success(Seq()))
        }
      }

      observable.toSeq.subscribe(onNext _, onError _, onCompleted _)
      promise.future
    }
  }

}
