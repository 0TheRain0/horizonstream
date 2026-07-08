package com.cmsoft.horizonstream.common.ext

import androidx.lifecycle.LiveData
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import org.reactivestreams.Publisher

fun <T> Observable<T>.toLiveData(): LiveData<T> {
    return object : LiveData<T>() {
        private var disposable: Disposable? = null
        override fun onActive() {
            disposable = subscribe({ postValue(it) }, { it.printStackTrace() })
        }
        override fun onInactive() {
            disposable?.dispose()
        }
    }
}

fun <T> Single<T>.toLiveData(): LiveData<T> {
    return object : LiveData<T>() {
        private var disposable: Disposable? = null
        override fun onActive() {
            disposable = subscribe({ postValue(it) }, { it.printStackTrace() })
        }
        override fun onInactive() {
            disposable?.dispose()
        }
    }
}

fun <T> Publisher<T>.toLiveData(): LiveData<T> {
    return object : LiveData<T>() {
        private var subscription: org.reactivestreams.Subscription? = null
        override fun onActive() {
            subscribe(object : org.reactivestreams.Subscriber<T> {
                override fun onSubscribe(s: org.reactivestreams.Subscription) {
                    subscription = s
                    s.request(Long.MAX_VALUE)
                }
                override fun onNext(t: T) {
                    postValue(t)
                }
                override fun onError(t: Throwable) {
                    t.printStackTrace()
                }
                override fun onComplete() {}
            })
        }
        override fun onInactive() {
            subscription?.cancel()
        }
    }
}