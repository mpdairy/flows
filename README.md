# flows

Flows is a library to for distributed workflow processing in
Clojure/ClojureScript. Right now it is limited to a single machine
using async threads, but eventually it should auto-integrate with Onyx
on the back-end so you can create cluster-side tasks that can be added
seemlessly in the workflow, which would be especially useful for
webstacks.

## Current usage

```clj
(def workflow [[:in :a] [:a :b] [:b :out]])


```

## Usage

FIXME

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
