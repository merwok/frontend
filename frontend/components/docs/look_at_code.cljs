(ns frontend.components.docs.look-at-code)

(def article
  {:title "Does CircleCI look at my code?"
   :last-updated "Feb 2, 2013"
   :url :look-at-code
   :content [:div
             [:p
              "Nobody from Circle manually looks at your code, without permission.We will occasionally ask permission to debug why your code isn't working, but we will never look at your code without this permission.Our"
              [:a {:href "/privacy#security"} "security policy"]
              "goes into more detail about our safeguards to ensure the security of your code."]]})

